import {FieldValue} from "firebase-admin/firestore";
import {firebaseAdminFirestore, firebaseAdminMessaging} from "./firebaseAdmin.js";
import {buildRemoteMessageNotificationData} from "./notificationPreferenceDomain.js";

export interface MetadataOnlyMessageNotificationRecipient {
  uid: string;
  unreadCount: number;
}

export interface MetadataOnlyMessageNotificationCommand {
  messageId: string;
  recipients: readonly MetadataOnlyMessageNotificationRecipient[];
  roomId: string;
  senderUid: string;
}

export interface MetadataOnlyMessageNotificationResult {
  failureCount: number;
  successCount: number;
}

export async function sendMetadataOnlyMessageNotification(
  command: MetadataOnlyMessageNotificationCommand,
): Promise<MetadataOnlyMessageNotificationResult> {
  const unreadCountsByRecipientUid = new Map(
    command.recipients.map((recipient) => [recipient.uid, recipient.unreadCount]),
  );
  const recipientUids = [...unreadCountsByRecipientUid.keys()];
  if (recipientUids.length === 0) return {failureCount: 0, successCount: 0};

  const deviceSnapshots = await firebaseAdminFirestore
    .collection("devices")
    .where("ownerUid", "in", recipientUids)
    .where("active", "==", true)
    .get();
  const deviceRecords = deviceSnapshots.docs.flatMap((snapshot) => {
    const device: unknown = snapshot.data();
    if (
      !isRecord(device) ||
      typeof device.installationId !== "string" ||
      typeof device.ownerUid !== "string" ||
      !recipientUids.includes(device.ownerUid)
    ) {
      return [];
    }
    return [{
      installationId: device.installationId,
      ownerUid: device.ownerUid,
      reference: snapshot.ref,
    }];
  });
  if (deviceRecords.length === 0) return {failureCount: 0, successCount: 0};

  const sendResult = await firebaseAdminMessaging.sendEach(
    deviceRecords.map((device) => ({
      android: {
        collapseKey: `room_${command.roomId}`,
        priority: "high" as const,
        ttl: 24 * 60 * 60 * 1000,
      },
      data: buildRemoteMessageNotificationData({
        messageId: command.messageId,
        roomId: command.roomId,
        senderUid: command.senderUid,
        unreadCount: unreadCountsByRecipientUid.get(device.ownerUid) ?? 1,
      }),
      fid: device.installationId,
    })),
  );

  const invalidInstallationWrites = firebaseAdminFirestore.batch();
  sendResult.responses.forEach((response, index) => {
    const errorCode = response.error?.code;
    if (
      errorCode === "messaging/invalid-registration-token" ||
      errorCode === "messaging/registration-token-not-registered"
    ) {
      const deviceRecord = deviceRecords[index];
      if (deviceRecord) {
        invalidInstallationWrites.update(deviceRecord.reference, {
          active: false,
          disabledAt: FieldValue.serverTimestamp(),
        });
      }
    }
  });
  await invalidInstallationWrites.commit();

  return {
    failureCount: sendResult.failureCount,
    successCount: sendResult.successCount,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
