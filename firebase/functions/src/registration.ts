import {randomUUID} from "node:crypto";
import {Timestamp, type DocumentSnapshot} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  buildAccountProfileDocument,
  createFirebaseAccountIdentity,
  deleteOrDisableFirebaseAccount,
} from "./accountCreation.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {
  buildRegistrationRateLimitId,
  digestInvitationCode,
  parseInviteRegistrationCommand,
  resolveInitialAccountState,
  type AccountState,
  type InviteRegistrationCommand,
} from "./identity.js";

const PUBLIC_REGISTRATION_FAILURE =
  "Registration could not be completed. Check the invitation and account details.";
const REGISTRATION_RATE_LIMIT_WINDOW_MILLIS = 15 * 60 * 1000;
const REGISTRATION_RATE_LIMIT_MAXIMUM_ATTEMPTS = 10;
const REGISTRATION_RESERVATION_TTL_MILLIS = 10 * 60 * 1000;

interface RegistrationReservation {
  accountState: AccountState;
  invitationId: string;
  reservationId: string;
  usernameNormalized: string;
}

interface RegistrationReceipt {
  accountState: AccountState;
  usernameNormalized: string;
}

class RegistrationRejected extends Error {}

export const registerWithInvite = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<RegistrationReceipt> => {
    const rateLimitSubject = resolveRateLimitSubject(
      request.rawRequest.ip,
      request.rawRequest.socket.remoteAddress,
      isDemoFirebaseEmulator(),
    );
    await recordRegistrationAttempt(rateLimitSubject);

    let command: InviteRegistrationCommand;
    try {
      command = parseInviteRegistrationCommand(request.data);
    } catch {
      throw new HttpsError("invalid-argument", "Check the registration details and try again.");
    }

    let reservation: RegistrationReservation;
    try {
      reservation = await reserveInvitationAndUsername(command);
    } catch (error) {
      if (error instanceof RegistrationRejected) {
        throw new HttpsError("permission-denied", PUBLIC_REGISTRATION_FAILURE);
      }
      throw new HttpsError("internal", PUBLIC_REGISTRATION_FAILURE);
    }

    let createdAccountUid: string | null = null;
    try {
      createdAccountUid = await createFirebaseAccountIdentity(
        command,
        "USER",
        reservation.accountState,
      );
      await completeRegistration(command, reservation, createdAccountUid);
      return {
        accountState: reservation.accountState,
        usernameNormalized: command.usernameNormalized,
      };
    } catch {
      await cleanupFailedRegistration(reservation, createdAccountUid);
      throw new HttpsError("internal", PUBLIC_REGISTRATION_FAILURE);
    }
  },
);

export function resolveRateLimitSubject(
  requestIp: string | undefined,
  remoteAddress: string | undefined,
  allowEmulatorFallback = false,
): string {
  const normalized = requestIp?.trim() || remoteAddress?.trim();
  if (normalized) {
    return normalized;
  }
  if (allowEmulatorFallback) {
    return "firebase-functions-emulator";
  }
  throw new HttpsError("unavailable", "Registration is temporarily unavailable.");
}

function isDemoFirebaseEmulator(): boolean {
  return process.env.FUNCTIONS_EMULATOR === "true" &&
    process.env.GCLOUD_PROJECT?.startsWith("demo-") === true;
}

async function recordRegistrationAttempt(rateLimitSubject: string): Promise<void> {
  const rateLimitReference = firebaseAdminFirestore.doc(
    `registrationRateLimits/${buildRegistrationRateLimitId(rateLimitSubject)}`,
  );
  const now = Timestamp.now();
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(rateLimitReference);
    if (!snapshot.exists) {
      transaction.create(rateLimitReference, {
        attemptCount: 1,
        updatedAt: now,
        windowStartedAt: now,
      });
      return;
    }

    const attemptCount = readInteger(snapshot, "attemptCount");
    const windowStartedAt = readTimestamp(snapshot, "windowStartedAt");
    if (attemptCount === null || windowStartedAt === null) {
      throw new HttpsError("resource-exhausted", "Too many registration attempts. Try again later.");
    }
    if (now.toMillis() - windowStartedAt.toMillis() >= REGISTRATION_RATE_LIMIT_WINDOW_MILLIS) {
      transaction.update(rateLimitReference, {
        attemptCount: 1,
        updatedAt: now,
        windowStartedAt: now,
      });
      return;
    }
    if (attemptCount >= REGISTRATION_RATE_LIMIT_MAXIMUM_ATTEMPTS) {
      throw new HttpsError("resource-exhausted", "Too many registration attempts. Try again later.");
    }
    transaction.update(rateLimitReference, {
      attemptCount: attemptCount + 1,
      updatedAt: now,
    });
  });
}

async function reserveInvitationAndUsername(
  command: InviteRegistrationCommand,
): Promise<RegistrationReservation> {
  const invitationId = digestInvitationCode(command.invitationCode);
  const reservationId = randomUUID();
  const invitationReference = firebaseAdminFirestore.doc(`invitations/${invitationId}`);
  const usernameReference = firebaseAdminFirestore.doc(`usernames/${command.usernameNormalized}`);
  const registrationSettingsReference = firebaseAdminFirestore.doc("systemSettings/registration");
  const reservationReference = firebaseAdminFirestore.doc(
    `registrationReservations/${reservationId}`,
  );
  const now = Timestamp.now();

  return firebaseAdminFirestore.runTransaction(async (transaction) => {
    const invitationSnapshot = await transaction.get(invitationReference);
    const usernameSnapshot = await transaction.get(usernameReference);
    const registrationSettingsSnapshot = await transaction.get(registrationSettingsReference);
    const remainingUses = readInteger(invitationSnapshot, "remainingUses");
    const maximumUses = readInteger(invitationSnapshot, "maximumUses");
    const expiresAt = readTimestamp(invitationSnapshot, "expiresAt");
    const invitationState = invitationSnapshot.get("state");
    const revokedAt = invitationSnapshot.get("revokedAt");
    if (
      !invitationSnapshot.exists ||
      invitationState !== "ACTIVE" ||
      revokedAt !== null ||
      remainingUses === null ||
      maximumUses === null ||
      remainingUses < 1 ||
      maximumUses < remainingUses ||
      expiresAt === null ||
      expiresAt.toMillis() <= now.toMillis() ||
      usernameSnapshot.exists
    ) {
      throw new RegistrationRejected();
    }

    const accountState = resolveInitialAccountState(
      registrationSettingsSnapshot.get("approvalRequired"),
    );
    const nextRemainingUses = remainingUses - 1;
    transaction.update(invitationReference, {
      lastReservedAt: now,
      lastReservationId: reservationId,
      redeemedCount: maximumUses - nextRemainingUses,
      remainingUses: nextRemainingUses,
      state: nextRemainingUses === 0 ? "EXHAUSTED" : "ACTIVE",
    });
    transaction.create(usernameReference, {
      createdAt: now,
      expiresAt: Timestamp.fromMillis(now.toMillis() + REGISTRATION_RESERVATION_TTL_MILLIS),
      reservationId,
      state: "RESERVED",
      usernameNormalized: command.usernameNormalized,
    });
    transaction.create(reservationReference, {
      accountState,
      createdAt: now,
      invitationId,
      state: "RESERVED",
      usernameNormalized: command.usernameNormalized,
    });
    return {
      accountState,
      invitationId,
      reservationId,
      usernameNormalized: command.usernameNormalized,
    };
  });
}

async function completeRegistration(
  command: InviteRegistrationCommand,
  reservation: RegistrationReservation,
  accountUid: string,
): Promise<void> {
  const completedAt = Timestamp.now();
  const writes = firebaseAdminFirestore.batch();
  writes.create(
    firebaseAdminFirestore.doc(`profiles/${accountUid}`),
    buildAccountProfileDocument(command, "USER", reservation.accountState, completedAt),
  );
  writes.update(firebaseAdminFirestore.doc(`usernames/${command.usernameNormalized}`), {
    claimedAt: completedAt,
    state: "CLAIMED",
    uid: accountUid,
  });
  writes.update(
    firebaseAdminFirestore.doc(`registrationReservations/${reservation.reservationId}`),
    {
      completedAt,
      state: "COMPLETE",
      uid: accountUid,
    },
  );
  writes.create(
    firebaseAdminFirestore.doc(`inviteRedemptions/${reservation.reservationId}`),
    {
      accountState: reservation.accountState,
      invitationId: reservation.invitationId,
      redeemedAt: completedAt,
      uid: accountUid,
      usernameNormalized: command.usernameNormalized,
    },
  );
  writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
    actorUid: accountUid,
    createdAt: completedAt,
    eventType: "ACCOUNT_REGISTERED_WITH_INVITE",
    targetUid: accountUid,
  });
  await writes.commit();
}

async function cleanupFailedRegistration(
  reservation: RegistrationReservation,
  accountUid: string | null,
): Promise<void> {
  const failedStages: string[] = [];
  if (accountUid) {
    const authCleanupFailure = await deleteOrDisableFirebaseAccount(accountUid);
    if (authCleanupFailure) failedStages.push(authCleanupFailure);
  }

  try {
    await rollbackInvitationAndUsername(reservation);
  } catch {
    failedStages.push("FIRESTORE_RESERVATION_ROLLBACK_FAILED");
  }
  if (failedStages.length > 0) {
    await firebaseAdminFirestore.doc(`registrationCleanupFailures/${reservation.reservationId}`).set({
      failedStages,
      recordedAt: Timestamp.now(),
      reservationId: reservation.reservationId,
    }).catch(() => undefined);
  }
}

async function rollbackInvitationAndUsername(
  reservation: RegistrationReservation,
): Promise<void> {
  const invitationReference = firebaseAdminFirestore.doc(
    `invitations/${reservation.invitationId}`,
  );
  const usernameReference = firebaseAdminFirestore.doc(
    `usernames/${reservation.usernameNormalized}`,
  );
  const reservationReference = firebaseAdminFirestore.doc(
    `registrationReservations/${reservation.reservationId}`,
  );
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const reservationSnapshot = await transaction.get(reservationReference);
    const usernameSnapshot = await transaction.get(usernameReference);
    const invitationSnapshot = await transaction.get(invitationReference);
    if (!reservationSnapshot.exists || reservationSnapshot.get("state") !== "RESERVED") {
      return;
    }
    if (usernameSnapshot.get("reservationId") === reservation.reservationId) {
      transaction.delete(usernameReference);
    }
    if (invitationSnapshot.exists) {
      const remainingUses = readInteger(invitationSnapshot, "remainingUses");
      const maximumUses = readInteger(invitationSnapshot, "maximumUses");
      const redeemedCount = readInteger(invitationSnapshot, "redeemedCount");
      if (remainingUses !== null && maximumUses !== null && redeemedCount !== null) {
        const restoredUses = Math.min(maximumUses, remainingUses + 1);
        const wasRevoked = invitationSnapshot.get("revokedAt") instanceof Timestamp;
        transaction.update(invitationReference, {
          redeemedCount: Math.max(0, redeemedCount - 1),
          remainingUses: restoredUses,
          state: wasRevoked ? "REVOKED" : "ACTIVE",
        });
      }
    }
    transaction.delete(reservationReference);
  });
}

function readInteger(snapshot: DocumentSnapshot, fieldName: string): number | null {
  const field: unknown = snapshot.get(fieldName);
  return typeof field === "number" && Number.isSafeInteger(field) ? field : null;
}

function readTimestamp(snapshot: DocumentSnapshot, fieldName: string): Timestamp | null {
  const field: unknown = snapshot.get(fieldName);
  return field instanceof Timestamp ? field : null;
}
