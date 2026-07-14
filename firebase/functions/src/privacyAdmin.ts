import {createHash, randomUUID} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  requireActiveAccount,
  requireRecentActiveAccount,
} from "./accountAuthorization.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {parseTargetUid} from "./domain.js";

const RECENT_ACCOUNT_AUTHENTICATION_SECONDS = 5 * 60;
const MAXIMUM_BLOCK_RESULTS = 500;

export const getOwnPrivacyState = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{blockedUids: string[]; deletionRequestPending: boolean}> => {
    const {uid} = await requireActiveAccount(request.auth);
    const [blocks, deletionRequest] = await Promise.all([
      firebaseAdminFirestore.collection("blocks")
        .where("blockerUid", "==", uid)
        .limit(MAXIMUM_BLOCK_RESULTS)
        .get(),
      firebaseAdminFirestore.doc(`accountDeletionRequests/${uid}`).get(),
    ]);
    return {
      blockedUids: blocks.docs.map((block) => {
        const blockedUid = block.get("blockedUid");
        if (typeof blockedUid !== "string") {
          throw new HttpsError("data-loss", "A privacy record is malformed.");
        }
        return blockedUid;
      }),
      deletionRequestPending: deletionRequest.get("state") === "PENDING",
    };
  },
);

export const setUserBlocked = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{blocked: boolean; targetUid: string}> => {
    const {uid} = await requireActiveAccount(request.auth);
    const command = parseBlockCommand(request.data);
    if (command.targetUid === uid) {
      throw new HttpsError("invalid-argument", "An account cannot block itself.");
    }
    const targetReference = firebaseAdminFirestore.doc(`profiles/${command.targetUid}`);
    const blockReference = firebaseAdminFirestore.doc(
      `blocks/${buildUserBlockDocumentId(uid, command.targetUid)}`,
    );
    const changedAt = Timestamp.now();
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [target, existingBlock] = await Promise.all([
        transaction.get(targetReference),
        transaction.get(blockReference),
      ]);
      if (
        !target.exists ||
        target.get("accountState") !== "ACTIVE" ||
        target.get("allowed") !== true
      ) {
        throw new HttpsError("not-found", "The selected account is unavailable.");
      }
      if (command.blocked && !existingBlock.exists) {
        transaction.create(blockReference, {
          blockedUid: command.targetUid,
          blockerUid: uid,
          createdAt: changedAt,
        });
        transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
          actorUid: uid,
          createdAt: changedAt,
          eventType: "USER_BLOCKED",
          targetUid: command.targetUid,
        });
      } else if (!command.blocked && existingBlock.exists) {
        transaction.delete(blockReference);
        transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
          actorUid: uid,
          createdAt: changedAt,
          eventType: "USER_UNBLOCKED",
          targetUid: command.targetUid,
        });
      }
    });
    return command;
  },
);

export const requestAccountDeletion = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deletionRequestPending: true}> => {
    const {uid} = await requireRecentActiveAccount(
      request.auth,
      RECENT_ACCOUNT_AUTHENTICATION_SECONDS,
    );
    const requestedAt = Timestamp.now();
    const requestReference = firebaseAdminFirestore.doc(`accountDeletionRequests/${uid}`);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const existingRequest = await transaction.get(requestReference);
      if (existingRequest.get("state") === "PENDING") return;
      transaction.set(requestReference, {
        requestedAt,
        requestedBy: uid,
        state: "PENDING",
      });
      transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
        actorUid: uid,
        createdAt: requestedAt,
        eventType: "ACCOUNT_DELETION_REQUESTED",
        targetUid: uid,
      });
    });
    return {deletionRequestPending: true};
  },
);

export const cancelAccountDeletionRequest = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deletionRequestPending: false}> => {
    const {uid} = await requireActiveAccount(request.auth);
    const requestReference = firebaseAdminFirestore.doc(`accountDeletionRequests/${uid}`);
    const cancelledAt = Timestamp.now();
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const existingRequest = await transaction.get(requestReference);
      if (!existingRequest.exists) return;
      transaction.delete(requestReference);
      transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
        actorUid: uid,
        createdAt: cancelledAt,
        eventType: "ACCOUNT_DELETION_REQUEST_CANCELLED",
        targetUid: uid,
      });
    });
    return {deletionRequestPending: false};
  },
);

export function buildUserBlockDocumentId(blockerUid: string, blockedUid: string): string {
  if (!isUid(blockerUid) || !isUid(blockedUid) || blockerUid === blockedUid) {
    throw new Error("Two distinct account identifiers are required.");
  }
  return createHash("sha256").update(`${blockerUid}:${blockedUid}`, "utf8").digest("hex");
}

export function buildReciprocalBlockReferences(firstUid: string, secondUid: string) {
  return [
    firebaseAdminFirestore.doc(`blocks/${buildUserBlockDocumentId(firstUid, secondUid)}`),
    firebaseAdminFirestore.doc(`blocks/${buildUserBlockDocumentId(secondUid, firstUid)}`),
  ] as const;
}

function parseBlockCommand(input: unknown): {blocked: boolean; targetUid: string} {
  if (!isRecord(input) || typeof input.blocked !== "boolean") {
    throw new HttpsError("invalid-argument", "Block command is invalid.");
  }
  try {
    return {blocked: input.blocked, targetUid: parseTargetUid(input)};
  } catch {
    throw new HttpsError("invalid-argument", "Block command is invalid.");
  }
}

function isUid(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
