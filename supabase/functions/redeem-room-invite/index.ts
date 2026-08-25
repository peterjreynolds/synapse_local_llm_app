import {
  createServiceClient,
  enforceAccountAccessRateLimit,
  expectSingleObject,
  expectStringField,
  readRuntimeSecrets,
  verifyActor,
} from "../_shared/backend.ts";
import { parseRedeemRoomInviteRequest } from "../_shared/contracts.ts";
import { postgresByteaFromHex, sha256Hex } from "../_shared/crypto.ts";
import { HttpError, jsonResponse, serveJsonEndpoint } from "../_shared/http.ts";

Deno.serve((request: Request) =>
  serveJsonEndpoint(request, 4 * 1024, async (body: unknown) => {
    const redemption = parseRedeemRoomInviteRequest(body);
    const secrets = await readRuntimeSecrets();
    const serviceClient = createServiceClient(secrets);
    const actor = await verifyActor(request, serviceClient);
    await enforceAccountAccessRateLimit(
      serviceClient,
      request,
      "ROOM_REDEEM",
      secrets.rateLimitPepper,
    );

    try {
      const { data, error } = await serviceClient.rpc("_edge_redeem_room_membership_invite", {
        p_actor_user_id: actor.userId,
        p_auth_session_id: actor.authSessionId,
        p_code_digest: postgresByteaFromHex(await sha256Hex(redemption.inviteCode)),
        p_redemption_id: redemption.redemptionId,
      });
      if (error !== null) throw new Error("Room invite redemption failed");
      const receipt = expectSingleObject(data);
      const membershipEpoch = receipt.membership_epoch;
      if (!Number.isSafeInteger(membershipEpoch)) {
        throw new Error("Room membership receipt was malformed");
      }

      return jsonResponse(200, {
        membership: {
          room_id: expectStringField(receipt, "room_id"),
          user_id: expectStringField(receipt, "user_id"),
          membership_epoch: membershipEpoch,
          completed_at: expectStringField(receipt, "completed_at"),
        },
      });
    } catch {
      throw new HttpError(403, "The room invitation could not be redeemed.");
    }
  })
);
