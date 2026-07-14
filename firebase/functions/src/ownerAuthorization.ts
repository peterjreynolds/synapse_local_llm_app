import {HttpsError} from "firebase-functions/v2/https";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export async function requireActiveOwner(authContext: unknown): Promise<string> {
  if (!isRecord(authContext)) {
    throw new HttpsError("unauthenticated", "Sign in before using owner administration.");
  }
  const uid = authContext.uid;
  const token = authContext.token;
  if (
    typeof uid !== "string" ||
    uid.length === 0 ||
    !isRecord(token) ||
    token.accountState !== "ACTIVE" ||
    token.role !== "OWNER"
  ) {
    throw new HttpsError("permission-denied", "Owner access is required.");
  }
  const profile = await firebaseAdminFirestore.doc(`profiles/${uid}`).get();
  if (
    !profile.exists ||
    profile.get("allowed") !== true ||
    profile.get("accountState") !== "ACTIVE" ||
    profile.get("role") !== "OWNER"
  ) {
    throw new HttpsError("permission-denied", "Owner access is required.");
  }
  return uid;
}

export async function requireRecentActiveOwner(
  authContext: unknown,
  maximumAgeSeconds: number,
): Promise<string> {
  const ownerUid = await requireActiveOwner(authContext);
  if (!isRecord(authContext)) {
    throw new HttpsError("unauthenticated", "Sign in before using owner administration.");
  }
  const token = authContext.token;
  const authenticationTime = isRecord(token) ? token.auth_time : null;
  const nowSeconds = Math.floor(Date.now() / 1_000);
  if (!isRecentAuthentication(authenticationTime, nowSeconds, maximumAgeSeconds)) {
    throw new HttpsError("failed-precondition", "Recent owner sign-in is required.");
  }
  return ownerUid;
}

export function isRecentAuthentication(
  authenticationTime: unknown,
  nowSeconds: number,
  maximumAgeSeconds: number,
): boolean {
  return typeof authenticationTime === "number" &&
    Number.isSafeInteger(authenticationTime) &&
    Number.isSafeInteger(nowSeconds) &&
    Number.isSafeInteger(maximumAgeSeconds) &&
    maximumAgeSeconds > 0 &&
    authenticationTime <= nowSeconds &&
    nowSeconds - authenticationTime <= maximumAgeSeconds;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
