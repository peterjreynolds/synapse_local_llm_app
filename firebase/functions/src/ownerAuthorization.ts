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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
