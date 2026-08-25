import {
  createServiceClient,
  expectObjectRows,
  expectSingleObject,
  expectStringField,
  readRuntimeSecrets,
} from "../_shared/backend.ts";
import { assertEmptyObject } from "../_shared/contracts.ts";
import { postgresByteaFromHex, sha256Hex, timingSafeSecretEquals } from "../_shared/crypto.ts";
import { HttpError, jsonResponse, serveJsonEndpoint } from "../_shared/http.ts";
import { parseLeasedAttachmentBatch, removeLeasedAttachmentObjects } from "../_shared/retention.ts";

Deno.serve(async (request: Request) => {
  let secrets;
  try {
    secrets = await readRuntimeSecrets();
    const expectedPurgeSecret = secrets.purgeSecret;
    const suppliedPurgeSecret = request.headers.get("x-synapse-purge-secret") ?? "";
    if (
      expectedPurgeSecret.length !== 43 ||
      !/^[A-Za-z0-9_-]{43}$/u.test(expectedPurgeSecret) ||
      !(await timingSafeSecretEquals(suppliedPurgeSecret, expectedPurgeSecret))
    ) {
      throw new HttpError(401, "Authentication is required.");
    }
  } catch (error: unknown) {
    if (error instanceof HttpError) {
      return jsonResponse(error.status, { error: error.publicMessage });
    }
    return jsonResponse(500, { error: "The request could not be completed." });
  }

  return serveJsonEndpoint(request, 2 * 1024, async (body: unknown) => {
    assertEmptyObject(body);
    const serviceClient = createServiceClient(secrets);
    const runtimeSecretDigest = postgresByteaFromHex(
      await sha256Hex(secrets.purgeSecret),
    );

    const { data: healthData, error: healthError } = await serviceClient.rpc(
      "_edge_retention_configuration_health",
      { p_runtime_purge_secret_sha256: runtimeSecretDigest },
    );
    if (healthError !== null) throw new Error("Retention health verification failed");
    const health = expectSingleObject(healthData);
    if (health.configuration_valid !== true) {
      throw new HttpError(503, "Retention configuration is incomplete.");
    }

    const { data: leaseData, error: leaseError } = await serviceClient.rpc(
      "_edge_lease_expired_attachment_batch",
      { p_batch_limit: 100 },
    );
    if (leaseError !== null) throw new Error("Attachment purge lease acquisition failed");
    const batch = parseLeasedAttachmentBatch(expectObjectRows(leaseData));

    if (batch === null) {
      const { data: heartbeatData, error: heartbeatError } = await serviceClient.rpc(
        "_edge_record_storage_purge_heartbeat",
        { p_runtime_purge_secret_sha256: runtimeSecretDigest },
      );
      if (heartbeatError !== null) throw new Error("Storage purge heartbeat failed");
      const heartbeat = expectSingleObject(heartbeatData);
      return jsonResponse(200, {
        receipt: {
          correlation_id: expectStringField(heartbeat, "correlation_id"),
          messages_deleted: 0,
          attachment_objects_deleted: 0,
          completed_at: expectStringField(heartbeat, "completed_at"),
        },
        health: {
          configuration_valid: true,
          relational_job_active: health.relational_job_active === true,
          storage_job_active: health.storage_job_active === true,
          project_url_configured: health.project_url_configured === true,
          purge_secret_matches: health.purge_secret_matches === true,
        },
      });
    }

    await removeLeasedAttachmentObjects(batch, async (paths) => {
      const { error } = await serviceClient.storage.from("encrypted-attachments").remove([
        ...paths,
      ]);
      if (error !== null) throw new Error("Storage API object removal failed");
    });

    const { data: finalizeData, error: finalizeError } = await serviceClient.rpc(
      "_edge_finalize_expired_attachment_message_purge",
      { p_lease_id: batch.leaseId },
    );
    if (finalizeError !== null) throw new Error("Attachment purge finalization failed");
    const receipt = expectSingleObject(finalizeData);

    return jsonResponse(200, {
      receipt: {
        correlation_id: expectStringField(receipt, "correlation_id"),
        messages_deleted: receipt.messages_deleted,
        attachment_objects_deleted: receipt.attachment_objects_deleted,
        completed_at: expectStringField(receipt, "completed_at"),
      },
      health: {
        configuration_valid: true,
        relational_job_active: health.relational_job_active === true,
        storage_job_active: health.storage_job_active === true,
        project_url_configured: health.project_url_configured === true,
        purge_secret_matches: health.purge_secret_matches === true,
      },
    });
  });
});
