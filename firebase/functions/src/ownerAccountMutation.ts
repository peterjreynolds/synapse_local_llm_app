import {randomUUID} from "node:crypto";
import {FieldValue, Timestamp, type DocumentReference} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  buildAccountProfileDocument,
  createFirebaseAccountIdentity,
  deleteOrDisableFirebaseAccount,
} from "./accountCreation.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminAuth,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {
  buildAccountClaims,
  normalizeAccountDisplayName,
  normalizeUsername,
  validateNewAccountPassword,
  type AccountRole,
} from "./identity.js";
import {requireActiveOwner, requireRecentActiveOwner} from "./ownerAuthorization.js";

const RECENT_OWNER_AUTHENTICATION_SECONDS = 5 * 60;

interface CreateOwnerAccountCommand {
  displayName: string;
  password: string;
  requirePasswordChange: boolean;
  usernameNormalized: string;
}

export const createAccountForUser = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{targetUid: string; usernameNormalized: string}> => {
    const ownerUid = await requireRecentActiveOwner(
      request.auth,
      RECENT_OWNER_AUTHENTICATION_SECONDS,
    );
    const command = parseCreateOwnerAccountCommand(request.data);
    const reservationId = randomUUID();
    const usernameReference = firebaseAdminFirestore.doc(`usernames/${command.usernameNormalized}`);
    const reservedAt = Timestamp.now();
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const username = await transaction.get(usernameReference);
      if (username.exists) {
        throw new HttpsError("already-exists", "The account could not be created.");
      }
      transaction.create(usernameReference, {
        createdAt: reservedAt,
        reservationId,
        state: "RESERVED",
        usernameNormalized: command.usernameNormalized,
      });
    });

    let targetUid: string | null = null;
    try {
      targetUid = await createFirebaseAccountIdentity(
        command,
        "USER",
        "ACTIVE",
        command.requirePasswordChange,
      );
      const createdAt = Timestamp.now();
      const writes = firebaseAdminFirestore.batch();
      writes.create(
        firebaseAdminFirestore.doc(`profiles/${targetUid}`),
        buildAccountProfileDocument(
          command,
          "USER",
          "ACTIVE",
          createdAt,
          command.requirePasswordChange,
        ),
      );
      writes.update(usernameReference, {
        claimedAt: createdAt,
        reservationId: FieldValue.delete(),
        state: "CLAIMED",
        uid: targetUid,
      });
      writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
        actorUid: ownerUid,
        createdAt,
        eventType: "ACCOUNT_CREATED_BY_OWNER",
        requirePasswordChange: command.requirePasswordChange,
        targetUid,
      });
      await writes.commit();
      return {targetUid, usernameNormalized: command.usernameNormalized};
    } catch (error) {
      await cleanupFailedOwnerAccountCreation({
        ownerUid,
        reservationId,
        targetUid,
        usernameNormalized: command.usernameNormalized,
      });
      if (error instanceof HttpsError) throw error;
      throw new HttpsError("internal", "The account could not be created.");
    }
  },
);

export const setOwnerAccountEnabled = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{accountState: "ACTIVE" | "DISABLED"; targetUid: string}> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const command = parseTargetEnabledCommand(request.data);
    if (command.targetUid === ownerUid) {
      throw new HttpsError("failed-precondition", "The owner account cannot disable itself.");
    }
    const profileReference = firebaseAdminFirestore.doc(`profiles/${command.targetUid}`);
    const profile = await profileReference.get();
    if (!profile.exists) throw new HttpsError("not-found", "Account was not found.");
    const role = readAssignableRole(profile.get("role"));
    const mustChangePassword = profile.get("mustChangePassword") === true;
    const accountState = command.enabled ? "ACTIVE" : "DISABLED";

    if (!command.enabled) {
      await updateProfileAccountState(
        profileReference,
        ownerUid,
        command.targetUid,
        accountState,
        "ACCOUNT_DISABLED",
      );
      await firebaseAdminAuth.setCustomUserClaims(
        command.targetUid,
        buildAccountClaims(role, accountState, mustChangePassword),
      );
      await firebaseAdminAuth.revokeRefreshTokens(command.targetUid);
      await firebaseAdminAuth.updateUser(command.targetUid, {disabled: true});
    } else {
      await firebaseAdminAuth.updateUser(command.targetUid, {disabled: false});
      await firebaseAdminAuth.setCustomUserClaims(
        command.targetUid,
        buildAccountClaims(role, accountState, mustChangePassword),
      );
      await updateProfileAccountState(
        profileReference,
        ownerUid,
        command.targetUid,
        accountState,
        "ACCOUNT_ENABLED",
      );
    }
    return {accountState, targetUid: command.targetUid};
  },
);

export const revokeOwnerAccountSessions = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{targetUid: string}> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const targetUid = parseTargetUid(request.data);
    if (targetUid === ownerUid) {
      throw new HttpsError("failed-precondition", "Use local sign-out for the current owner session.");
    }
    await firebaseAdminAuth.getUser(targetUid);
    await firebaseAdminAuth.revokeRefreshTokens(targetUid);
    await firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`).create({
      actorUid: ownerUid,
      createdAt: Timestamp.now(),
      eventType: "ACCOUNT_SESSIONS_REVOKED",
      targetUid,
    });
    return {targetUid};
  },
);

export const deleteOwnerAccount = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{targetUid: string}> => {
    const ownerUid = await requireRecentActiveOwner(
      request.auth,
      RECENT_OWNER_AUTHENTICATION_SECONDS,
    );
    const command = parseDeleteAccountCommand(request.data);
    if (command.targetUid === ownerUid) {
      throw new HttpsError("failed-precondition", "The owner account cannot delete itself.");
    }
    const profileReference = firebaseAdminFirestore.doc(`profiles/${command.targetUid}`);
    const profile = await profileReference.get();
    if (!profile.exists) throw new HttpsError("not-found", "Account was not found.");
    const usernameNormalized = profile.get("usernameNormalized");
    if (
      typeof usernameNormalized !== "string" ||
      command.confirmUsernameNormalized !== usernameNormalized
    ) {
      throw new HttpsError("failed-precondition", "Account deletion confirmation did not match.");
    }

    await profileReference.update({
      accountState: "DISABLED",
      allowed: false,
      directoryVisible: false,
      online: false,
      updatedAt: Timestamp.now(),
    });
    await firebaseAdminAuth.revokeRefreshTokens(command.targetUid);
    await firebaseAdminAuth.updateUser(command.targetUid, {disabled: true});
    await firebaseAdminAuth.deleteUser(command.targetUid);

    const [devices, rooms, outgoingBlocks, incomingBlocks] = await Promise.all([
      firebaseAdminFirestore.collection("devices").where("ownerUid", "==", command.targetUid).get(),
      firebaseAdminFirestore.collection("rooms")
        .where("memberIds", "array-contains", command.targetUid)
        .get(),
      firebaseAdminFirestore.collection("blocks").where("blockerUid", "==", command.targetUid).get(),
      firebaseAdminFirestore.collection("blocks").where("blockedUid", "==", command.targetUid).get(),
    ]);
    const writer = firebaseAdminFirestore.bulkWriter();
    for (const device of devices.docs) writer.delete(device.ref);
    for (const block of [...outgoingBlocks.docs, ...incomingBlocks.docs]) writer.delete(block.ref);
    for (const room of rooms.docs) {
      writer.set(
        room.ref,
        {activeMemberIds: FieldValue.arrayRemove(command.targetUid)},
        {merge: true},
      );
      writer.set(
        room.ref.collection("members").doc(command.targetUid),
        {active: false, removedAt: FieldValue.serverTimestamp(), removedBy: ownerUid},
        {merge: true},
      );
    }
    writer.delete(firebaseAdminFirestore.doc(`usernames/${usernameNormalized}`));
    writer.delete(firebaseAdminFirestore.doc(`accountDeletionRequests/${command.targetUid}`));
    writer.delete(profileReference);
    await writer.close();
    await firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`).create({
      actorUid: ownerUid,
      createdAt: Timestamp.now(),
      eventType: "ACCOUNT_DELETED",
      targetUid: command.targetUid,
      usernameNormalized,
    });
    return {targetUid: command.targetUid};
  },
);

export function parseCreateOwnerAccountCommand(input: unknown): CreateOwnerAccountCommand {
  if (!isRecord(input)) {
    throw new HttpsError("invalid-argument", "Account details are required.");
  }
  if (
    typeof input.username !== "string" ||
    typeof input.displayName !== "string" ||
    typeof input.password !== "string" ||
    typeof input.requirePasswordChange !== "boolean"
  ) {
    throw new HttpsError("invalid-argument", "Account details are invalid.");
  }
  try {
    return {
      displayName: normalizeAccountDisplayName(input.displayName),
      password: validateNewAccountPassword(input.password),
      requirePasswordChange: input.requirePasswordChange,
      usernameNormalized: normalizeUsername(input.username),
    };
  } catch {
    throw new HttpsError("invalid-argument", "Account details are invalid.");
  }
}

async function updateProfileAccountState(
  profileReference: DocumentReference,
  ownerUid: string,
  targetUid: string,
  accountState: "ACTIVE" | "DISABLED",
  eventType: "ACCOUNT_ENABLED" | "ACCOUNT_DISABLED",
): Promise<void> {
  const updatedAt = Timestamp.now();
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const profile = await transaction.get(profileReference);
    if (!profile.exists) throw new HttpsError("not-found", "Account was not found.");
    if (profile.get("accountState") === accountState) return;
    transaction.update(profileReference, {
      accountState,
      allowed: accountState === "ACTIVE",
      directoryVisible: accountState === "ACTIVE",
      online: false,
      updatedAt,
    });
    transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
      actorUid: ownerUid,
      createdAt: updatedAt,
      eventType,
      targetUid,
    });
  });
}

async function releaseOwnerUsernameReservation(
  usernameNormalized: string,
  reservationId: string,
): Promise<void> {
  const usernameReference = firebaseAdminFirestore.doc(`usernames/${usernameNormalized}`);
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const username = await transaction.get(usernameReference);
    if (username.get("reservationId") === reservationId) transaction.delete(usernameReference);
  });
}

async function cleanupFailedOwnerAccountCreation(command: {
  ownerUid: string;
  reservationId: string;
  targetUid: string | null;
  usernameNormalized: string;
}): Promise<void> {
  const failedStages: string[] = [];
  if (command.targetUid) {
    const authCleanupFailure = await deleteOrDisableFirebaseAccount(command.targetUid);
    if (authCleanupFailure) failedStages.push(authCleanupFailure);
  }
  try {
    await releaseOwnerUsernameReservation(command.usernameNormalized, command.reservationId);
  } catch {
    failedStages.push("FIRESTORE_USERNAME_RESERVATION_RELEASE_FAILED");
  }
  if (failedStages.length === 0) return;
  await firebaseAdminFirestore
    .doc(`accountCreationCleanupFailures/${command.reservationId}`)
    .set({
      actorUid: command.ownerUid,
      failedStages,
      recordedAt: Timestamp.now(),
      reservationId: command.reservationId,
      targetUid: command.targetUid,
      usernameNormalized: command.usernameNormalized,
    })
    .catch(() => undefined);
}

function parseTargetEnabledCommand(input: unknown): {enabled: boolean; targetUid: string} {
  if (!isRecord(input) || typeof input.enabled !== "boolean") {
    throw new HttpsError("invalid-argument", "Account state command is invalid.");
  }
  return {enabled: input.enabled, targetUid: parseTargetUid(input)};
}

function parseDeleteAccountCommand(input: unknown): {
  confirmUsernameNormalized: string;
  targetUid: string;
} {
  if (!isRecord(input) || typeof input.confirmUsername !== "string") {
    throw new HttpsError("invalid-argument", "Account deletion command is invalid.");
  }
  let confirmUsernameNormalized: string;
  try {
    confirmUsernameNormalized = normalizeUsername(input.confirmUsername);
  } catch {
    throw new HttpsError("invalid-argument", "Account deletion command is invalid.");
  }
  return {confirmUsernameNormalized, targetUid: parseTargetUid(input)};
}

function parseTargetUid(input: unknown): string {
  if (!isRecord(input) || typeof input.targetUid !== "string") {
    throw new HttpsError("invalid-argument", "Account target is invalid.");
  }
  const targetUid = input.targetUid.trim();
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(targetUid)) {
    throw new HttpsError("invalid-argument", "Account target is invalid.");
  }
  return targetUid;
}

function readAssignableRole(value: unknown): AccountRole {
  if (value !== "USER" && value !== "ADMIN") {
    throw new HttpsError("failed-precondition", "The account role cannot be changed here.");
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
