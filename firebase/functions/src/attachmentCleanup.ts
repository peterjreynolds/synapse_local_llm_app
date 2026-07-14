import {Timestamp} from "firebase-admin/firestore";
import {onDocumentUpdated} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {deleteAttachmentObjects, requireAttachmentUpload} from "./attachmentMutation.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";

export const cleanupDeletedMessageAttachments = onDocumentUpdated(
  {
    document: "rooms/{roomId}/messages/{messageId}",
    region: FIREBASE_FUNCTIONS_REGION,
    retry: true,
  },
  async (event): Promise<void> => {
    const before = event.data?.before;
    const after = event.data?.after;
    if (!before || !after || before.get("deletedAt") !== null || !(after.get("deletedAt") instanceof Timestamp)) {
      return;
    }
    const attachmentIds = readAttachmentIds(after.get("attachmentIds"));
    await cleanupUploads(
      attachmentIds,
      (upload) => upload.roomId === event.params.roomId && upload.messageId === event.params.messageId,
    );
  },
);

export const cleanupExpiredAttachmentUploads = onSchedule(
  {
    region: FIREBASE_FUNCTIONS_REGION,
    schedule: "every 24 hours",
    retryCount: 3,
  },
  async (): Promise<void> => {
    const expiredSnapshots = await firebaseAdminFirestore.collection("attachmentUploads")
      .where("expiresAt", "<=", Timestamp.now())
      .limit(MAXIMUM_CLEANUP_BATCH)
      .get();
    await cleanupUploads(
      expiredSnapshots.docs.map((document) => document.id),
      (upload) => upload.status === "PENDING" || upload.status === "READY" || upload.status === "CANCELLED",
    );
  },
);

async function cleanupUploads(
  attachmentIds: string[],
  shouldClean: (upload: ReturnType<typeof requireAttachmentUpload>) => boolean,
): Promise<void> {
  await Promise.all(attachmentIds.map(async (attachmentId) => {
    const uploadReference = firebaseAdminFirestore.doc(`attachmentUploads/${attachmentId}`);
    const upload = await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(uploadReference);
      if (!snapshot.exists) return null;
      const currentUpload = requireAttachmentUpload(snapshot.data());
      if (!shouldClean(currentUpload) || currentUpload.status === "CLEANED") return null;
      transaction.update(uploadReference, {
        expiresAt: Timestamp.now(),
        status: "CANCELLED",
        updatedAt: Timestamp.now(),
      });
      return currentUpload;
    });
    if (!upload) return;
    await deleteAttachmentObjects(upload);
    const cleanedAt = Timestamp.now();
    await uploadReference.update({
      cleanedAt,
      expiresAt: null,
      status: "CLEANED",
      updatedAt: cleanedAt,
    });
  }));
}

function readAttachmentIds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const attachmentIds = value.filter(
    (attachmentId): attachmentId is string => typeof attachmentId === "string" &&
      /^attachment-[a-f0-9-]{36}$/.test(attachmentId),
  );
  return attachmentIds.length === value.length ? [...new Set(attachmentIds)] : [];
}

const MAXIMUM_CLEANUP_BATCH = 100;
