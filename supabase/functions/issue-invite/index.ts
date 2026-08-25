import {
  createServiceClient,
  expectSingleObject,
  expectStringField,
  readRuntimeSecrets,
  verifyActor,
} from "../_shared/backend.ts";
import { parseIssueInviteRequest } from "../_shared/contracts.ts";
import { deriveInviteCode, postgresByteaFromHex, sha256Hex } from "../_shared/crypto.ts";
import { HttpError, jsonResponse, serveJsonEndpoint } from "../_shared/http.ts";

Deno.serve((request: Request) =>
  serveJsonEndpoint(request, 4 * 1024, async (body: unknown) => {
    const inviteRequest = parseIssueInviteRequest(body);
    const secrets = await readRuntimeSecrets();
    const serviceClient = createServiceClient(secrets);
    const actor = await verifyActor(request, serviceClient);
    const inviteCode = await deriveInviteCode(
      secrets.inviteDerivationKey,
      actor.userId,
      inviteRequest.kind,
      inviteRequest.kind === "ROOM_MEMBERSHIP" ? inviteRequest.roomId : null,
      inviteRequest.clientMutationId,
    );
    const inviteDigest = postgresByteaFromHex(await sha256Hex(inviteCode));

    try {
      const rpcName = inviteRequest.kind === "ACCOUNT_REGISTRATION"
        ? "_edge_issue_account_registration_invite"
        : "_edge_issue_room_membership_invite";
      const rpcArguments: Record<string, unknown> = {
        p_actor_user_id: actor.userId,
        p_auth_session_id: actor.authSessionId,
        p_client_mutation_id: inviteRequest.clientMutationId,
        p_code_digest: inviteDigest,
        p_expires_in_seconds: inviteRequest.expiresInSeconds,
      };
      if (inviteRequest.kind === "ROOM_MEMBERSHIP") {
        rpcArguments.p_room_id = inviteRequest.roomId;
      }
      const { data, error } = await serviceClient.rpc(rpcName, rpcArguments);
      if (error !== null) throw new Error("Invite issuance failed");
      const receipt = expectSingleObject(data);
      const roomId = typeof receipt.room_id === "string" ? receipt.room_id : null;

      return jsonResponse(201, {
        invite: {
          id: expectStringField(receipt, "invite_id"),
          kind: expectStringField(receipt, "invite_kind"),
          room_id: roomId,
          code: inviteCode,
          expires_at: expectStringField(receipt, "expires_at"),
        },
      });
    } catch {
      throw new HttpError(403, "The invitation could not be issued.");
    }
  })
);
