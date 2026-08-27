import { type SupabaseClient } from "@supabase/supabase-js";
import {
  createServiceClient,
  enforceAccountAccessRateLimit,
  expectObjectRows,
  expectSingleObject,
  expectStringField,
  generateInternalAccountIdentity,
  publicSession,
  readRuntimeSecrets,
  reserveDeviceRegistrationForSession,
  usernameDigest,
} from "../_shared/backend.ts";
import { parseRedeemAccountInviteRequest } from "../_shared/contracts.ts";
import { postgresByteaFromHex, sha256Hex } from "../_shared/crypto.ts";
import { HttpError, jsonResponse, serveJsonEndpoint } from "../_shared/http.ts";

interface RegistrationInspection {
  readonly state: "AVAILABLE" | "REDEEMED";
  readonly kind: "BOOTSTRAP" | "ACCOUNT_REGISTRATION";
  readonly existingUserId: string | null;
  readonly existingInternalEmail: string | null;
  readonly completedAt: string | null;
}

async function inspectRegistration(
  serviceClient: SupabaseClient,
  codeDigest: string,
  redemptionId: string,
): Promise<RegistrationInspection | null> {
  const { data, error } = await serviceClient.rpc("_edge_inspect_account_registration", {
    p_code_digest: codeDigest,
    p_redemption_id: redemptionId,
  });
  if (error !== null) throw new Error("Registration capability inspection failed");
  const rows = expectObjectRows(data);
  if (rows.length === 0) return null;
  if (rows.length !== 1) throw new Error("Registration capability inspection was ambiguous");
  const row = rows[0]!;
  const state = expectStringField(row, "registration_state");
  const kind = expectStringField(row, "registration_kind");
  if (!(["AVAILABLE", "REDEEMED"] as const).includes(state as "AVAILABLE" | "REDEEMED")) {
    throw new Error("Registration capability state was malformed");
  }
  if (
    !(["BOOTSTRAP", "ACCOUNT_REGISTRATION"] as const).includes(
      kind as "BOOTSTRAP" | "ACCOUNT_REGISTRATION",
    )
  ) {
    throw new Error("Registration capability kind was malformed");
  }
  return {
    state: state as RegistrationInspection["state"],
    kind: kind as RegistrationInspection["kind"],
    existingUserId: typeof row.existing_user_id === "string" ? row.existing_user_id : null,
    existingInternalEmail: typeof row.existing_internal_email === "string"
      ? row.existing_internal_email
      : null,
    completedAt: typeof row.completed_at === "string" ? row.completed_at : null,
  };
}

Deno.serve((request: Request) =>
  serveJsonEndpoint(request, 32 * 1024, async (body: unknown) => {
    const registration = parseRedeemAccountInviteRequest(body);
    const secrets = await readRuntimeSecrets();
    const serviceClient = createServiceClient(secrets);
    await enforceAccountAccessRateLimit(
      serviceClient,
      request,
      "REGISTER",
      secrets.rateLimitPepper,
    );

    const inviteDigest = postgresByteaFromHex(await sha256Hex(registration.inviteCode));
    let createdUserId: string | null = null;
    let registrationCommitted = false;
    let registrationDefinitelyUncommitted = false;
    try {
      const initialInspection = await inspectRegistration(
        serviceClient,
        inviteDigest,
        registration.redemptionId,
      );
      if (initialInspection === null) throw new Error("Registration capability is unavailable");

      const generatedIdentity = initialInspection.state === "AVAILABLE"
        ? generateInternalAccountIdentity()
        : null;
      const internalEmail = generatedIdentity?.internalEmail ??
        initialInspection.existingInternalEmail;
      if (internalEmail === null) throw new Error("Registration receipt is incomplete");
      if (generatedIdentity !== null) {
        const { data, error } = await serviceClient.auth.admin.createUser({
          id: generatedIdentity.userId,
          email: internalEmail,
          password: registration.password,
          email_confirm: true,
          app_metadata: { synapse_private_registration_authority: true },
        });
        if (
          error !== null || data.user === null || data.user.id !== generatedIdentity.userId
        ) {
          throw new Error("Auth identity creation failed");
        }
        createdUserId = data.user.id;
      }
      const expectedUserId = createdUserId ?? initialInspection.existingUserId;
      if (expectedUserId === null) throw new Error("Registration receipt is incomplete");

      const normalizedUsernameDigest = await usernameDigest(
        secrets.usernamePepper,
        registration.username,
      );
      const { data: redemptionData, error: redemptionError } = await serviceClient.rpc(
        "_edge_redeem_account_registration",
        {
          p_code_digest: inviteDigest,
          p_redemption_id: registration.redemptionId,
          p_username_digest: postgresByteaFromHex(normalizedUsernameDigest),
          p_internal_email: internalEmail,
          p_user_id: expectedUserId,
          p_display_name: registration.displayName,
        },
      );
      if (redemptionError !== null) throw new Error("Registration transaction failed");
      const receipt = expectSingleObject(redemptionData);
      registrationCommitted = true;

      const { data: signInData, error: signInError } = await serviceClient.auth.signInWithPassword({
        email: internalEmail,
        password: registration.password,
      });
      if (
        signInError !== null || signInData.user === null || signInData.session === null ||
        signInData.user.id !== expectedUserId
      ) {
        throw new Error("Auth session creation failed");
      }
      const deviceReservation = await reserveDeviceRegistrationForSession(
        serviceClient,
        expectedUserId,
        signInData.session,
        registration.deviceId,
      );

      return jsonResponse(201, {
        registration: {
          kind: expectStringField(receipt, "registration_kind"),
          user_id: expectStringField(receipt, "user_id"),
          completed_at: expectStringField(receipt, "completed_at"),
        },
        device_registration: {
          user_id: deviceReservation.userId,
          device_id: deviceReservation.deviceId,
          signal_device_id: deviceReservation.signalDeviceId,
          expires_at: deviceReservation.expiresAt,
        },
        session: publicSession(signInData.session),
      });
    } catch {
      if (createdUserId !== null && !registrationCommitted) {
        try {
          const reconciliation = await inspectRegistration(
            serviceClient,
            inviteDigest,
            registration.redemptionId,
          );
          registrationCommitted = reconciliation?.state === "REDEEMED" &&
            reconciliation.existingUserId === createdUserId;
          registrationDefinitelyUncommitted = reconciliation?.state === "AVAILABLE";
        } catch {
          // An uncertain database outcome must not trigger destructive compensation.
        }
        if (!registrationCommitted && registrationDefinitelyUncommitted) {
          await serviceClient.auth.admin.deleteUser(createdUserId).catch(() => undefined);
        }
      }
      throw new HttpError(403, "Registration could not be completed.");
    }
  })
);
