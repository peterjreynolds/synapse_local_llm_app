import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {normalizeUsername, type AccountRole, type AccountState} from "./identity.js";
import {requireActiveOwner} from "./ownerAuthorization.js";

const MAXIMUM_ACCOUNT_RESULTS = 100;

interface OwnerAccountSummary {
  accountState: AccountState;
  createdAtMillis: number | null;
  displayName: string;
  lastSeenAtMillis: number | null;
  mustChangePassword: boolean;
  role: AccountRole;
  uid: string;
  usernameNormalized: string;
}

export const listOwnerAccounts = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{accounts: OwnerAccountSummary[]}> => {
    await requireActiveOwner(request.auth);
    const searchPrefix = parseAccountSearchPrefix(request.data);
    let query = firebaseAdminFirestore.collection("profiles")
      .orderBy("usernameNormalized")
      .limit(MAXIMUM_ACCOUNT_RESULTS);
    if (searchPrefix) {
      query = query.startAt(searchPrefix).endAt(`${searchPrefix}\uf8ff`);
    }
    const profiles = await query.get();
    const accounts = profiles.docs.map((profile) => parseOwnerAccountSummary(profile.id, profile.data()));
    return {accounts};
  },
);

function parseAccountSearchPrefix(input: unknown): string | null {
  if (input === undefined || input === null) return null;
  if (!isRecord(input) || input.searchPrefix === undefined || input.searchPrefix === null) {
    return null;
  }
  if (typeof input.searchPrefix !== "string") {
    throw new HttpsError("invalid-argument", "Account search is invalid.");
  }
  const trimmed = input.searchPrefix.trim();
  if (trimmed.length === 0) return null;
  try {
    return normalizeUsername(trimmed);
  } catch {
    throw new HttpsError("invalid-argument", "Account search is invalid.");
  }
}

function parseOwnerAccountSummary(
  uid: string,
  profile: Record<string, unknown>,
): OwnerAccountSummary {
  const accountState = parseAccountState(profile.accountState);
  const role = parseAccountRole(profile.role);
  const displayName = profile.displayName;
  const usernameNormalized = profile.usernameNormalized;
  if (
    typeof displayName !== "string" ||
    displayName.length === 0 ||
    typeof usernameNormalized !== "string"
  ) {
    throw new HttpsError("data-loss", "An account profile is malformed.");
  }
  return {
    accountState,
    createdAtMillis: readTimestampMillis(profile.createdAt),
    displayName,
    lastSeenAtMillis: readTimestampMillis(profile.lastSeenAt),
    mustChangePassword: profile.mustChangePassword === true,
    role,
    uid,
    usernameNormalized,
  };
}

function parseAccountState(value: unknown): AccountState {
  if (
    value !== "PENDING_APPROVAL" &&
    value !== "ACTIVE" &&
    value !== "REJECTED" &&
    value !== "DISABLED"
  ) {
    throw new HttpsError("data-loss", "An account profile has an invalid state.");
  }
  return value;
}

function parseAccountRole(value: unknown): AccountRole {
  if (value !== "OWNER" && value !== "ADMIN" && value !== "USER") {
    throw new HttpsError("data-loss", "An account profile has an invalid role.");
  }
  return value;
}

function readTimestampMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
