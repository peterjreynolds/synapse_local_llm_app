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

interface OwnDeviceSummary {
  active: boolean;
  deviceId: string;
  platform: "ANDROID";
  updatedAtMillis: number | null;
}

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

export const listOwnDevices = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{devices: OwnDeviceSummary[]}> => {
    const {uid} = await requireActiveAccount(request.auth);
    const devices = await firebaseAdminFirestore.collection("devices")
      .where("ownerUid", "==", uid)
      .get();
    return {
      devices: devices.docs.map((device) => ({
        active: device.get("active") === true,
        deviceId: device.id,
        platform: "ANDROID",
        updatedAtMillis: readTimestampMillis(device.get("updatedAt")),
      })),
    };
  },
);

export const registerOwnDevice = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deviceId: string; registered: true}> => {
    const {uid} = await requireActiveAccount(request.auth);
    const installationId = parseInstallationId(request.data);
    const deviceId = createHash("sha256").update(installationId, "utf8").digest("hex");
    const deviceReference = firebaseAdminFirestore.doc(`devices/${deviceId}`);
    const registeredAt = Timestamp.now();
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const existingDevice = await transaction.get(deviceReference);
      if (existingDevice.exists && existingDevice.get("ownerUid") !== uid) {
        throw new HttpsError("permission-denied", "Device registration is unavailable.");
      }
      transaction.set(deviceReference, {
        active: true,
        createdAt: existingDevice.exists ? existingDevice.get("createdAt") : registeredAt,
        installationId,
        ownerUid: uid,
        platform: "ANDROID",
        updatedAt: registeredAt,
      });
    });
    return {deviceId, registered: true};
  },
);

export const removeOwnDevice = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deviceId: string; removed: boolean}> => {
    const {uid} = await requireActiveAccount(request.auth);
    const deviceId = parseDeviceId(request.data);
    const deviceReference = firebaseAdminFirestore.doc(`devices/${deviceId}`);
    const device = await deviceReference.get();
    if (!device.exists) return {deviceId, removed: false};
    if (device.get("ownerUid") !== uid) {
      throw new HttpsError("not-found", "Device registration was not found.");
    }
    const removedAt = Timestamp.now();
    const writes = firebaseAdminFirestore.batch();
    writes.delete(deviceReference);
    writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
      actorUid: uid,
      createdAt: removedAt,
      deviceId,
      eventType: "OWN_DEVICE_REGISTRATION_REMOVED",
      targetUid: uid,
    });
    await writes.commit();
    return {deviceId, removed: true};
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

function parseDeviceId(input: unknown): string {
  if (!isRecord(input) || typeof input.deviceId !== "string") {
    throw new HttpsError("invalid-argument", "Device command is invalid.");
  }
  const deviceId = input.deviceId.trim().toLocaleLowerCase("en-US");
  if (!/^[a-f0-9]{64}$/.test(deviceId)) {
    throw new HttpsError("invalid-argument", "Device command is invalid.");
  }
  return deviceId;
}

function parseInstallationId(input: unknown): string {
  if (!isRecord(input) || typeof input.installationId !== "string") {
    throw new HttpsError("invalid-argument", "Device registration is invalid.");
  }
  const installationId = input.installationId;
  if (installationId.length < 16 || installationId.length > 256) {
    throw new HttpsError("invalid-argument", "Device registration is invalid.");
  }
  return installationId;
}

function readTimestampMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

function isUid(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
