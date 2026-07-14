import {randomUUID} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminAuth,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {buildAccountClaims, validateNewAccountPassword, type AccountRole} from "./identity.js";
import {enforceCallableRateLimit} from "./callableRateLimit.js";
import {isRecentAuthentication, requireRecentActiveOwner} from "./ownerAuthorization.js";

const RECENT_AUTHENTICATION_SECONDS = 5 * 60;

export const resetOwnerAccountPassword = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{mustChangePassword: boolean; targetUid: string}> => {
    const ownerUid = await requireRecentActiveOwner(
      request.auth,
      RECENT_AUTHENTICATION_SECONDS,
    );
    await enforceCallableRateLimit(ownerUid, "ownerMutation");
    const command = parseOwnerPasswordResetCommand(request.data);
    if (command.targetUid === ownerUid) {
      throw new HttpsError("failed-precondition", "Change the owner password from the profile screen.");
    }
    const profileReference = firebaseAdminFirestore.doc(`profiles/${command.targetUid}`);
    const profile = await profileReference.get();
    if (!profile.exists) throw new HttpsError("not-found", "Account was not found.");
    const role = readAssignableRole(profile.get("role"));
    const accountState = profile.get("accountState");
    if (accountState !== "ACTIVE") {
      throw new HttpsError("failed-precondition", "Only active accounts can receive a password reset.");
    }

    const changedAt = Timestamp.now();
    await profileReference.update({
      mustChangePassword: command.requirePasswordChange,
      updatedAt: changedAt,
    });
    await firebaseAdminAuth.updateUser(command.targetUid, {password: command.password});
    await firebaseAdminAuth.setCustomUserClaims(
      command.targetUid,
      buildAccountClaims(role, "ACTIVE", command.requirePasswordChange),
    );
    await firebaseAdminAuth.revokeRefreshTokens(command.targetUid);
    await firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`).create({
      actorUid: ownerUid,
      createdAt: changedAt,
      eventType: "ACCOUNT_PASSWORD_RESET",
      requirePasswordChange: command.requirePasswordChange,
      targetUid: command.targetUid,
    });
    return {
      mustChangePassword: command.requirePasswordChange,
      targetUid: command.targetUid,
    };
  },
);

export const completeRequiredPasswordChange = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{mustChangePassword: false}> => {
    const auth = parseRecentAccountAuth(request.auth);
    const profileReference = firebaseAdminFirestore.doc(`profiles/${auth.uid}`);
    const profile = await profileReference.get();
    if (
      !profile.exists ||
      profile.get("allowed") !== true ||
      profile.get("accountState") !== "ACTIVE"
    ) {
      throw new HttpsError("permission-denied", "The account is not active.");
    }
    const role = readAccountRole(profile.get("role"));
    await firebaseAdminAuth.setCustomUserClaims(
      auth.uid,
      buildAccountClaims(role, "ACTIVE", false),
    );
    const changedAt = Timestamp.now();
    const writes = firebaseAdminFirestore.batch();
    writes.update(profileReference, {
      mustChangePassword: false,
      updatedAt: changedAt,
    });
    writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
      actorUid: auth.uid,
      createdAt: changedAt,
      eventType: "REQUIRED_PASSWORD_CHANGE_COMPLETED",
      targetUid: auth.uid,
    });
    await writes.commit();
    return {mustChangePassword: false};
  },
);

export function parseOwnerPasswordResetCommand(input: unknown): {
  password: string;
  requirePasswordChange: boolean;
  targetUid: string;
} {
  if (
    !isRecord(input) ||
    typeof input.password !== "string" ||
    typeof input.requirePasswordChange !== "boolean" ||
    typeof input.targetUid !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(input.targetUid)
  ) {
    throw new HttpsError("invalid-argument", "Password reset details are invalid.");
  }
  try {
    return {
      password: validateNewAccountPassword(input.password),
      requirePasswordChange: input.requirePasswordChange,
      targetUid: input.targetUid,
    };
  } catch {
    throw new HttpsError("invalid-argument", "Password reset details are invalid.");
  }
}

function parseRecentAccountAuth(authContext: unknown): {uid: string} {
  if (!isRecord(authContext) || typeof authContext.uid !== "string" || !isRecord(authContext.token)) {
    throw new HttpsError("unauthenticated", "Sign in before completing the password change.");
  }
  const authenticationTime = authContext.token.auth_time;
  const nowSeconds = Math.floor(Date.now() / 1_000);
  if (!isRecentAuthentication(authenticationTime, nowSeconds, RECENT_AUTHENTICATION_SECONDS)) {
    throw new HttpsError("failed-precondition", "Recent sign-in is required.");
  }
  return {uid: authContext.uid};
}

function readAssignableRole(value: unknown): "USER" | "ADMIN" {
  if (value !== "USER" && value !== "ADMIN") {
    throw new HttpsError("failed-precondition", "The account role cannot be reset here.");
  }
  return value;
}

function readAccountRole(value: unknown): AccountRole {
  if (value !== "OWNER" && value !== "ADMIN" && value !== "USER") {
    throw new HttpsError("data-loss", "The account role is invalid.");
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
