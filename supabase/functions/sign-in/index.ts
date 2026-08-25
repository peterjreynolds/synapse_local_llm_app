import {
  createServiceClient,
  enforceAccountAccessRateLimit,
  expectObjectRows,
  publicSession,
  readRuntimeSecrets,
  reserveDeviceRegistrationForSession,
  usernameDigest,
} from "../_shared/backend.ts";
import { parseSignInRequest } from "../_shared/contracts.ts";
import { postgresByteaFromHex } from "../_shared/crypto.ts";
import { HttpError, jsonResponse, serveJsonEndpoint } from "../_shared/http.ts";

const NONEXISTENT_INTERNAL_EMAIL =
  "00000000-0000-0000-0000-000000000000@identity.synapse-private.invalid";

Deno.serve((request: Request) =>
  serveJsonEndpoint(request, 32 * 1024, async (body: unknown) => {
    const signIn = parseSignInRequest(body);
    const secrets = await readRuntimeSecrets();
    const serviceClient = createServiceClient(secrets);
    await enforceAccountAccessRateLimit(serviceClient, request, "SIGN_IN", secrets.rateLimitPepper);

    try {
      const normalizedUsernameDigest = await usernameDigest(
        secrets.usernamePepper,
        signIn.username,
      );
      const { data: loginData, error: loginError } = await serviceClient.rpc(
        "_edge_resolve_account_login",
        {
          p_username_digest: postgresByteaFromHex(normalizedUsernameDigest),
        },
      );
      if (loginError !== null) throw new Error("Account lookup failed");
      const loginRows = expectObjectRows(loginData);
      if (loginRows.length > 1) throw new Error("Account lookup was ambiguous");
      const loginRow = loginRows[0];
      const internalEmail = loginRow !== undefined && typeof loginRow.internal_email === "string"
        ? loginRow.internal_email
        : NONEXISTENT_INTERNAL_EMAIL;
      const expectedUserId = loginRow !== undefined && typeof loginRow.user_id === "string"
        ? loginRow.user_id
        : null;

      const { data: signInData, error: signInError } = await serviceClient.auth.signInWithPassword({
        email: internalEmail,
        password: signIn.password,
      });
      if (
        signInError !== null ||
        signInData.user === null ||
        signInData.session === null ||
        expectedUserId === null ||
        signInData.user.id !== expectedUserId
      ) {
        throw new Error("Credentials are invalid");
      }
      const deviceReservation = await reserveDeviceRegistrationForSession(
        serviceClient,
        expectedUserId,
        signInData.session,
        signIn.deviceId,
      );

      return jsonResponse(200, {
        account: { user_id: expectedUserId },
        device_registration: {
          user_id: deviceReservation.userId,
          device_id: deviceReservation.deviceId,
          signal_device_id: deviceReservation.signalDeviceId,
          expires_at: deviceReservation.expiresAt,
        },
        session: publicSession(signInData.session),
      });
    } catch {
      throw new HttpError(401, "The username or password is invalid.");
    }
  })
);
