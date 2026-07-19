import {FieldValue, Timestamp} from "firebase-admin/firestore";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {readCinderMessageSyncRecord} from "./cinderConversation.js";
import {
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_ASSISTANT_ROOM_ID,
  CINDER_PARTICIPANT_ID,
} from "./cinderDomain.js";
import {buildNotificationReceiptId} from "./domain.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {sendMetadataOnlyMessageNotification} from "./messageNotificationDelivery.js";
import {readNotificationPreferences} from "./notificationPreferenceDomain.js";

export const notifyCinderMessage = onDocumentCreated(
  {
    document: "cinderConversations/{accountUid}/messages/{messageId}",
    region: FIREBASE_FUNCTIONS_REGION,
    retry: true,
  },
  async (event): Promise<void> => {
    const messageSnapshot = event.data;
    if (!messageSnapshot) return;

    const accountUid = event.params.accountUid;
    const messageId = event.params.messageId;
    if (!isDedicatedCinderNotificationCandidate(messageSnapshot.data(), accountUid, messageId)) {
      return;
    }

    let message;
    try {
      message = readCinderMessageSyncRecord(messageSnapshot, accountUid);
    } catch {
      return;
    }
    if (message.authorKind !== "REMOTE_AI") return;

    const [profileSnapshot, preferenceSnapshot] = await firebaseAdminFirestore.getAll(
      firebaseAdminFirestore.doc(`profiles/${accountUid}`),
      firebaseAdminFirestore.doc(`notificationPreferences/${accountUid}`),
    );
    if (!profileSnapshot || !preferenceSnapshot) {
      throw new Error("Cinder notification authorization could not be resolved.");
    }
    const recipientEnabled = shouldNotifyDedicatedCinderAccount(
      profileSnapshot.data(),
      preferenceSnapshot.exists ? preferenceSnapshot.data() : undefined,
    );
    const receiptReference = firebaseAdminFirestore.doc(
      `notificationDeliveries/${buildNotificationReceiptId(event.id)}`,
    );
    try {
      await receiptReference.create({
        eventId: event.id,
        messageId,
        roomId: CINDER_ASSISTANT_ROOM_ID,
        startedAt: FieldValue.serverTimestamp(),
        state: "PROCESSING",
      });
    } catch (error) {
      if (isAlreadyExistsError(error)) return;
      throw error;
    }

    try {
      const result = await sendMetadataOnlyMessageNotification({
        messageId,
        recipients: recipientEnabled ? [{uid: accountUid, unreadCount: 1}] : [],
        roomId: CINDER_ASSISTANT_ROOM_ID,
        senderUid: message.senderUid,
      });
      await receiptReference.update({
        completedAt: Timestamp.now(),
        failureCount: result.failureCount,
        state: "COMPLETE",
        successCount: result.successCount,
      });
    } catch (error) {
      await receiptReference.delete();
      throw error;
    }
  },
);

export function isDedicatedCinderNotificationCandidate(
  input: unknown,
  accountUid: string,
  messageId: string,
): boolean {
  if (
    !/^[A-Za-z0-9_-]{1,128}$/.test(accountUid) ||
    !/^cinder-[a-f0-9]{64}$/.test(messageId) ||
    !isRecord(input)
  ) {
    return false;
  }
  return input.accountUid === accountUid &&
    input.clientMessageId === messageId &&
    input.roomId === CINDER_ASSISTANT_ROOM_ID &&
    input.authorKind === "REMOTE_AI" &&
    input.senderUid === CINDER_PARTICIPANT_ID &&
    input.aiParticipantId === CINDER_PARTICIPANT_ID &&
    input.aiProvenance === CINDER_AI_PROVENANCE &&
    input.aiProvider === CINDER_AI_PROVIDER &&
    input.assistantId === CINDER_ASSISTANT_ID;
}

export function shouldNotifyDedicatedCinderAccount(
  profileInput: unknown,
  preferenceInput: unknown,
): boolean {
  if (!isRecord(profileInput)) return false;
  return profileInput.allowed === true &&
    profileInput.accountState === "ACTIVE" &&
    profileInput.mustChangePassword === false &&
    readNotificationPreferences(preferenceInput).directMessages;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isAlreadyExistsError(error: unknown): boolean {
  return isRecord(error) && error.code === 6;
}
