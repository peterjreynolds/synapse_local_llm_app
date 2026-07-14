import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {
  buildAttachmentCommandDigest,
  buildAttachmentObjectPath,
  parseFinalizeRemoteAttachmentCommand,
  parsePrepareRemoteAttachmentCommand,
  PrepareRemoteAttachmentCommand,
} from "./attachmentDomain.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
  firebaseAdminStorage,
} from "./firebaseAdmin.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

interface AttachmentUploadDocument extends PrepareRemoteAttachmentCommand {
  actorUid: string;
  commandDigest: string;
  contentObjectPath: string;
  status: "ATTACHED" | "CANCELLED" | "CLEANED" | "PENDING" | "READY";
  thumbnailObjectPath: string | null;
}

export const prepareRemoteAttachment = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<AttachmentUploadReceipt> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parsePrepareRemoteAttachmentCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const messageReference = roomReference.collection("messages").doc(command.messageId);
    const uploadReference = firebaseAdminFirestore.doc(`attachmentUploads/${command.attachmentId}`);
    const commandDigest = buildAttachmentCommandDigest(command);
    const contentObjectPath = buildAttachmentObjectPath(
      command.roomId,
      command.messageId,
      command.attachmentId,
      "content",
    );
    const thumbnailObjectPath = command.kind === "IMAGE" ? buildAttachmentObjectPath(
      command.roomId,
      command.messageId,
      command.attachmentId,
      "thumbnail",
    ) : null;
    let status: AttachmentUploadDocument["status"] = "PENDING";
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const [messageSnapshot, uploadSnapshot] = await Promise.all([
        transaction.get(messageReference),
        transaction.get(uploadReference),
      ]);
      if (uploadSnapshot.exists) {
        const upload = requireAttachmentUpload(uploadSnapshot.data());
        if (
          upload.actorUid !== actorUid ||
          upload.commandDigest !== commandDigest ||
          upload.contentObjectPath !== contentObjectPath ||
          upload.thumbnailObjectPath !== thumbnailObjectPath
        ) {
          throw new HttpsError("already-exists", "The attachment identifier was already used.");
        }
        status = upload.status;
        return;
      }
      if (messageSnapshot.exists) {
        throw new HttpsError("failed-precondition", "Attachments must be prepared before the message is sent.");
      }
      const createdAt = Timestamp.now();
      transaction.create(uploadReference, {
        ...command,
        actorUid,
        commandDigest,
        contentObjectPath,
        createdAt,
        expiresAt: Timestamp.fromMillis(createdAt.toMillis() + UPLOAD_INTENT_LIFETIME_MILLIS),
        finalizedAt: null,
        status,
        thumbnailObjectPath,
        updatedAt: createdAt,
      });
    });
    return {
      attachmentId: command.attachmentId,
      contentObjectPath,
      messageId: command.messageId,
      roomId: command.roomId,
      status,
      thumbnailObjectPath,
    };
  },
);

export const finalizeRemoteAttachment = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<AttachmentUploadReceipt> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseFinalizeRemoteAttachmentCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const uploadReference = firebaseAdminFirestore.doc(`attachmentUploads/${command.attachmentId}`);
    const authorization = await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const uploadSnapshot = await transaction.get(uploadReference);
      const upload = requireAttachmentUpload(uploadSnapshot.data());
      requireMatchingUpload(upload, actorUid, command.roomId, command.messageId, command.attachmentId);
      if (upload.status === "CANCELLED" || upload.status === "CLEANED") {
        throw new HttpsError("failed-precondition", "The attachment upload is no longer active.");
      }
      return upload;
    });
    if (authorization.status === "PENDING") {
      await verifyUploadedObjects(authorization);
      await firebaseAdminFirestore.runTransaction(async (transaction) => {
        await requireActiveRoomActor(transaction, roomReference, actorUid);
        const uploadSnapshot = await transaction.get(uploadReference);
        const upload = requireAttachmentUpload(uploadSnapshot.data());
        requireMatchingUpload(upload, actorUid, command.roomId, command.messageId, command.attachmentId);
        if (upload.status === "READY" || upload.status === "ATTACHED") return;
        if (upload.status !== "PENDING") {
          throw new HttpsError("failed-precondition", "The attachment upload is no longer active.");
        }
        const finalizedAt = Timestamp.now();
        transaction.update(uploadReference, {
          expiresAt: Timestamp.fromMillis(finalizedAt.toMillis() + READY_UPLOAD_LIFETIME_MILLIS),
          finalizedAt,
          status: "READY",
          updatedAt: finalizedAt,
        });
      });
    }
    return {
      attachmentId: command.attachmentId,
      contentObjectPath: authorization.contentObjectPath,
      messageId: command.messageId,
      roomId: command.roomId,
      status: authorization.status === "ATTACHED" ? "ATTACHED" : "READY",
      thumbnailObjectPath: authorization.thumbnailObjectPath,
    };
  },
);

export const cancelRemoteAttachment = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{attachmentId: string; status: "CANCELLED" | "CLEANED"}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseFinalizeRemoteAttachmentCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const uploadReference = firebaseAdminFirestore.doc(`attachmentUploads/${command.attachmentId}`);
    const upload = await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const uploadSnapshot = await transaction.get(uploadReference);
      const currentUpload = requireAttachmentUpload(uploadSnapshot.data());
      requireMatchingUpload(currentUpload, actorUid, command.roomId, command.messageId, command.attachmentId);
      if (currentUpload.status === "ATTACHED") {
        throw new HttpsError("failed-precondition", "Sent attachments cannot be cancelled.");
      }
      if (currentUpload.status !== "CLEANED") {
        transaction.update(uploadReference, {
          expiresAt: Timestamp.now(),
          status: "CANCELLED",
          updatedAt: Timestamp.now(),
        });
      }
      return currentUpload;
    });
    if (upload.status !== "CLEANED") {
      await deleteAttachmentObjects(upload);
      await uploadReference.update({
        cleanedAt: Timestamp.now(),
        expiresAt: null,
        status: "CLEANED",
        updatedAt: Timestamp.now(),
      });
    }
    return {attachmentId: command.attachmentId, status: "CLEANED"};
  },
);

export async function deleteAttachmentObjects(upload: AttachmentUploadDocument): Promise<void> {
  const bucket = firebaseAdminStorage.bucket();
  await Promise.all([
    deleteObjectIfPresent(bucket.file(upload.contentObjectPath)),
    upload.thumbnailObjectPath ? deleteObjectIfPresent(bucket.file(upload.thumbnailObjectPath)) : Promise.resolve(),
  ]);
}

export function requireAttachmentUpload(value: unknown): AttachmentUploadDocument {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new HttpsError("not-found", "The attachment upload was not found.");
  }
  const upload = value as Record<string, unknown>;
  const parsed = parsePrepareRemoteAttachmentCommand(upload);
  if (
    typeof upload.actorUid !== "string" ||
    typeof upload.commandDigest !== "string" ||
    typeof upload.contentObjectPath !== "string" ||
    (upload.thumbnailObjectPath !== null && typeof upload.thumbnailObjectPath !== "string") ||
    (
      upload.status !== "ATTACHED" &&
      upload.status !== "CANCELLED" &&
      upload.status !== "CLEANED" &&
      upload.status !== "PENDING" &&
      upload.status !== "READY"
    )
  ) {
    throw new HttpsError("data-loss", "Attachment upload state is malformed.");
  }
  return {
    ...parsed,
    actorUid: upload.actorUid,
    commandDigest: upload.commandDigest,
    contentObjectPath: upload.contentObjectPath,
    status: upload.status,
    thumbnailObjectPath: upload.thumbnailObjectPath,
  };
}

async function verifyUploadedObjects(upload: AttachmentUploadDocument): Promise<void> {
  const bucket = firebaseAdminStorage.bucket();
  await verifyObjectMetadata(bucket.file(upload.contentObjectPath), upload, "content");
  if (upload.thumbnailObjectPath) {
    await verifyObjectMetadata(bucket.file(upload.thumbnailObjectPath), upload, "thumbnail");
  }
}

async function verifyObjectMetadata(
  file: ReturnType<ReturnType<typeof firebaseAdminStorage.bucket>["file"]>,
  upload: AttachmentUploadDocument,
  variant: "content" | "thumbnail",
): Promise<void> {
  let metadata;
  try {
    [metadata] = await file.getMetadata();
  } catch {
    throw new HttpsError("failed-precondition", `The attachment ${variant} upload is incomplete.`);
  }
  const expectedContentType = variant === "content" ? upload.mimeType : "image/jpeg";
  const expectedSize = variant === "content" ? upload.byteCount : null;
  const actualSize = Number(metadata.size);
  if (
    metadata.contentType !== expectedContentType ||
    !Number.isSafeInteger(actualSize) ||
    actualSize < 1 ||
    (expectedSize !== null && actualSize !== expectedSize) ||
    (variant === "thumbnail" && actualSize > MAXIMUM_THUMBNAIL_BYTES) ||
    metadata.metadata?.attachmentId !== upload.attachmentId ||
    metadata.metadata?.messageId !== upload.messageId ||
    metadata.metadata?.ownerUid !== upload.actorUid ||
    metadata.metadata?.roomId !== upload.roomId ||
    metadata.metadata?.variant !== variant
  ) {
    throw new HttpsError("failed-precondition", `The attachment ${variant} metadata is inconsistent.`);
  }
}

function requireMatchingUpload(
  upload: AttachmentUploadDocument,
  actorUid: string,
  roomId: string,
  messageId: string,
  attachmentId: string,
): void {
  if (
    upload.actorUid !== actorUid ||
    upload.roomId !== roomId ||
    upload.messageId !== messageId ||
    upload.attachmentId !== attachmentId
  ) {
    throw new HttpsError("permission-denied", "The attachment upload is unavailable.");
  }
}

async function deleteObjectIfPresent(
  file: ReturnType<ReturnType<typeof firebaseAdminStorage.bucket>["file"]>,
): Promise<void> {
  await file.delete({ignoreNotFound: true});
}

interface AttachmentUploadReceipt {
  attachmentId: string;
  contentObjectPath: string;
  messageId: string;
  roomId: string;
  status: AttachmentUploadDocument["status"];
  thumbnailObjectPath: string | null;
}

const MAXIMUM_THUMBNAIL_BYTES = 256 * 1024;
const READY_UPLOAD_LIFETIME_MILLIS = 60 * 60 * 1_000;
const UPLOAD_INTENT_LIFETIME_MILLIS = 24 * 60 * 60 * 1_000;
