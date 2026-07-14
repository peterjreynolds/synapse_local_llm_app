import {HttpsError} from "firebase-functions/v2/https";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";
import {isRecentAuthentication} from "./ownerAuthorization.js";

export interface ActiveAccountProfile {
  accountState: "ACTIVE";
  allowed: true;
  displayName: string;
  mustChangePassword: false;
  role: "OWNER" | "ADMIN" | "USER";
  username: string;
}

export async function requireActiveAccount(authContext: unknown): Promise<{
  profile: ActiveAccountProfile;
  uid: string;
}> {
  if (!isRecord(authContext) || typeof authContext.uid !== "string" || !isRecord(authContext.token)) {
    throw new HttpsError("unauthenticated", "Sign in before using this account action.");
  }
  const token = authContext.token;
  if (
    token.accountState !== "ACTIVE" ||
    token.mustChangePassword !== false ||
    (token.role !== "OWNER" && token.role !== "ADMIN" && token.role !== "USER")
  ) {
    throw new HttpsError("permission-denied", "An active account is required.");
  }
  const profileSnapshot = await firebaseAdminFirestore.doc(`profiles/${authContext.uid}`).get();
  const profile = profileSnapshot.data();
  assertActiveAccountProfile(profile);
  if (profile.role !== token.role) {
    throw new HttpsError("permission-denied", "An active account is required.");
  }
  return {profile, uid: authContext.uid};
}

export async function requireRecentActiveAccount(
  authContext: unknown,
  maximumAgeSeconds: number,
): Promise<{profile: ActiveAccountProfile; uid: string}> {
  const account = await requireActiveAccount(authContext);
  if (!isRecord(authContext) || !isRecord(authContext.token)) {
    throw new HttpsError("unauthenticated", "Sign in before using this account action.");
  }
  const nowSeconds = Math.floor(Date.now() / 1_000);
  if (!isRecentAuthentication(authContext.token.auth_time, nowSeconds, maximumAgeSeconds)) {
    throw new HttpsError("failed-precondition", "Recent sign-in is required.");
  }
  return account;
}

export function assertActiveAccountProfile(input: unknown): asserts input is ActiveAccountProfile {
  if (
    !isRecord(input) ||
    input.accountState !== "ACTIVE" ||
    input.allowed !== true ||
    typeof input.displayName !== "string" ||
    input.mustChangePassword !== false ||
    (input.role !== "OWNER" && input.role !== "ADMIN" && input.role !== "USER") ||
    typeof input.username !== "string"
  ) {
    throw new HttpsError("permission-denied", "An active account is required.");
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
