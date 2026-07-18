import {Timestamp} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {firebaseAdminAuth, firebaseAdminFirestore} from "./firebaseAdmin.js";

const SESSION_REVOCATION_FIELD = "sessionsRevokedAt";

export function assertSessionNotRevoked(
  authenticationTime: unknown,
  sessionsRevokedAt: unknown,
): void {
  if (
    typeof authenticationTime !== "number" ||
    !Number.isSafeInteger(authenticationTime) ||
    authenticationTime < 0
  ) {
    throw new HttpsError("unauthenticated", "Sign in before using this account action.");
  }
  if (sessionsRevokedAt === undefined) return;
  if (!(sessionsRevokedAt instanceof Timestamp)) denyRevokedSession();

  const revokedAtSeconds = Math.floor(sessionsRevokedAt.toMillis() / 1_000);
  if (authenticationTime <= revokedAtSeconds) denyRevokedSession();
}

export async function revokeAccountSessions(accountUid: string): Promise<Timestamp> {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(accountUid)) {
    throw new HttpsError("invalid-argument", "Account target is invalid.");
  }
  const revokedAt = Timestamp.now();
  await firebaseAdminFirestore.doc(`profiles/${accountUid}`).update({
    [SESSION_REVOCATION_FIELD]: revokedAt,
    updatedAt: revokedAt,
  });
  await firebaseAdminAuth.revokeRefreshTokens(accountUid);
  return revokedAt;
}

function denyRevokedSession(): never {
  throw new HttpsError("permission-denied", "Sign in again before using this account action.");
}
