import {randomUUID} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
  firebaseAdminMessaging,
} from "./firebaseAdmin.js";
import {requireActiveOwner} from "./ownerAuthorization.js";

interface OwnerDeviceSummary {
  active: boolean;
  deviceId: string;
  platform: "ANDROID";
  updatedAtMillis: number | null;
}

export const listOwnerDevices = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{devices: OwnerDeviceSummary[]; targetUid: string}> => {
    await requireActiveOwner(request.auth);
    const targetUid = parseTargetUid(request.data);
    const devices = await firebaseAdminFirestore.collection("devices")
      .where("ownerUid", "==", targetUid)
      .get();
    return {
      devices: devices.docs.map((device) => ({
        active: device.get("active") === true,
        deviceId: device.id,
        platform: "ANDROID",
        updatedAtMillis: readTimestampMillis(device.get("updatedAt")),
      })),
      targetUid,
    };
  },
);

export const removeOwnerDevice = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deviceId: string; targetUid: string}> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const command = parseDeviceCommand(request.data);
    const deviceReference = firebaseAdminFirestore.doc(`devices/${command.deviceId}`);
    const device = await deviceReference.get();
    if (!device.exists || device.get("ownerUid") !== command.targetUid) {
      throw new HttpsError("not-found", "Device registration was not found.");
    }
    const writes = firebaseAdminFirestore.batch();
    writes.delete(deviceReference);
    writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
      actorUid: ownerUid,
      createdAt: Timestamp.now(),
      deviceId: command.deviceId,
      eventType: "DEVICE_REGISTRATION_REMOVED",
      targetUid: command.targetUid,
    });
    await writes.commit();
    return command;
  },
);

export const sendOwnerTestPush = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deviceId: string; messageId: string; targetUid: string}> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const command = parseDeviceCommand(request.data);
    const device = await firebaseAdminFirestore.doc(`devices/${command.deviceId}`).get();
    const installationId = device.get("installationId");
    if (
      !device.exists ||
      device.get("ownerUid") !== command.targetUid ||
      device.get("active") !== true ||
      typeof installationId !== "string"
    ) {
      throw new HttpsError("not-found", "Active device registration was not found.");
    }
    const messageId = await firebaseAdminMessaging.send({
      android: {priority: "high", ttl: 5 * 60 * 1_000},
      data: {type: "SYNAPSE_ADMIN_TEST"},
      fid: installationId,
    });
    await firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`).create({
      actorUid: ownerUid,
      createdAt: Timestamp.now(),
      deviceId: command.deviceId,
      eventType: "DEVICE_TEST_PUSH_SENT",
      targetUid: command.targetUid,
    });
    return {...command, messageId};
  },
);

function parseDeviceCommand(input: unknown): {deviceId: string; targetUid: string} {
  if (!isRecord(input) || typeof input.deviceId !== "string") {
    throw new HttpsError("invalid-argument", "Device command is invalid.");
  }
  const deviceId = input.deviceId.trim().toLocaleLowerCase("en-US");
  if (!/^[a-f0-9]{64}$/.test(deviceId)) {
    throw new HttpsError("invalid-argument", "Device command is invalid.");
  }
  return {deviceId, targetUid: parseTargetUid(input)};
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

function readTimestampMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
