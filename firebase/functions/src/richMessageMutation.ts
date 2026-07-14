import {DocumentReference, DocumentSnapshot, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  buildMessageMutationReceiptId,
  buildMutationCommandDigest,
  buildReactionId,
  parseAcknowledgeRemoteMessagesCommand,
  parseDeleteRemoteMessageCommand,
  parseEditRemoteMessageCommand,
  parseSendRemoteMessageCommand,
  parseToggleRemoteReactionCommand,
} from "./richMessageDomain.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

interface ActiveMessageDocument {
  body: string;
  clientCreatedAt: Timestamp;
  deliveredToCount: number;
  deletedAt: Timestamp | null;
  reactionCounts: Record<string, number>;
  readByCount: number;
  replyToMessageId: string | null;
  revision: number;
  senderUid: string;
}

export const sendRemoteMessage = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{messageId: string; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseSendRemoteMessageCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const messageReference = roomReference.collection("messages").doc(command.messageId);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const messageSnapshot = await transaction.get(messageReference);
      if (messageSnapshot.exists) {
        requireIdempotentSend(messageSnapshot, command, actorUid);
        return;
      }
      if (command.replyToMessageId !== null) {
        const replySnapshot = await transaction.get(
          roomReference.collection("messages").doc(command.replyToMessageId),
        );
        const replyMessage = requireActiveMessage(replySnapshot);
        if (replyMessage.deletedAt !== null) {
          throw new HttpsError("failed-precondition", "The replied message is no longer available.");
        }
      }
      const createdAt = Timestamp.now();
      transaction.create(messageReference, {
        authorKind: "HUMAN",
        body: command.body,
        clientCreatedAt: Timestamp.fromMillis(command.clientCreatedAtMillis),
        clientMessageId: command.messageId,
        createdAt,
        deletedAt: null,
        deliveredToCount: 0,
        editedAt: null,
        reactionCounts: {},
        readByCount: 0,
        replyToMessageId: command.replyToMessageId,
        revision: 1,
        senderUid: actorUid,
      });
      transaction.update(roomReference, {
        latestMessage: {
          body: command.body,
          createdAt,
          messageId: command.messageId,
          senderUid: actorUid,
        },
        updatedAt: createdAt,
      });
    });
    return {messageId: command.messageId, revision: 1, roomId: command.roomId};
  },
);

export const editRemoteMessage = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{messageId: string; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseEditRemoteMessageCommand(request.data);
    return reviseOwnMessage(
      actorUid,
      command.roomId,
      command.messageId,
      command.mutationId,
      command.expectedRevision,
      "EDIT",
      (transaction, messageSnapshot, message, latestMessageId, roomReference) => {
        if (message.body === command.body) return message.revision;
        const revision = message.revision + 1;
        const editedAt = Timestamp.now();
        transaction.update(messageSnapshot.ref, {
          body: command.body,
          editedAt,
          revision,
        });
        if (latestMessageId === messageSnapshot.id) {
          transaction.update(roomReference, {
            "latestMessage.body": command.body,
            updatedAt: editedAt,
          });
        }
        return revision;
      },
      command,
    );
  },
);

export const deleteRemoteMessage = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{messageId: string; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseDeleteRemoteMessageCommand(request.data);
    return reviseOwnMessage(
      actorUid,
      command.roomId,
      command.messageId,
      command.mutationId,
      command.expectedRevision,
      "DELETE",
      (transaction, messageSnapshot, message, latestMessageId, roomReference) => {
        const revision = message.revision + 1;
        const deletedAt = Timestamp.now();
        transaction.update(messageSnapshot.ref, {
          body: "",
          deletedAt,
          reactionCounts: {},
          revision,
        });
        if (latestMessageId === messageSnapshot.id) {
          transaction.update(roomReference, {
            "latestMessage.body": "Message deleted",
            updatedAt: deletedAt,
          });
        }
        return revision;
      },
      command,
    );
  },
);

export const toggleRemoteReaction = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{
    emoji: string;
    messageId: string;
    reacted: boolean;
    reactionCount: number;
    roomId: string;
  }> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseToggleRemoteReactionCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const messageReference = roomReference.collection("messages").doc(command.messageId);
    const reactionReference = messageReference.collection("reactions").doc(
      buildReactionId(actorUid, command.emoji),
    );
    let reactionCount = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const [messageSnapshot, reactionSnapshot] = await Promise.all([
        transaction.get(messageReference),
        transaction.get(reactionReference),
      ]);
      const message = requireActiveMessage(messageSnapshot);
      if (message.deletedAt !== null) {
        throw new HttpsError("failed-precondition", "Deleted messages cannot receive reactions.");
      }
      if (reactionSnapshot.exists && (
        reactionSnapshot.get("actorUid") !== actorUid ||
        reactionSnapshot.get("emoji") !== command.emoji
      )) {
        throw new HttpsError("data-loss", "Reaction state is inconsistent.");
      }
      const currentlyReacted = reactionSnapshot.exists;
      const currentCount = message.reactionCounts[command.emoji] ?? 0;
      if (currentlyReacted === command.reacted) {
        reactionCount = currentCount;
        return;
      }
      const nextCount = currentCount + (command.reacted ? 1 : -1);
      if (nextCount < 0) throw new HttpsError("data-loss", "Reaction count is inconsistent.");
      const reactionCounts = {...message.reactionCounts};
      if (nextCount === 0) delete reactionCounts[command.emoji];
      else reactionCounts[command.emoji] = nextCount;
      if (Object.keys(reactionCounts).length > MAXIMUM_REACTION_TYPES) {
        throw new HttpsError("resource-exhausted", "The message reaction limit was reached.");
      }
      if (command.reacted) {
        transaction.create(reactionReference, {
          actorUid,
          createdAt: Timestamp.now(),
          emoji: command.emoji,
        });
      } else {
        transaction.delete(reactionReference);
      }
      transaction.update(messageReference, {reactionCounts});
      reactionCount = nextCount;
    });
    return {...command, reactionCount};
  },
);

export const acknowledgeRemoteMessages = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{acknowledgedCount: number; read: boolean; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseAcknowledgeRemoteMessagesCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    let acknowledgedCount = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const messageReferences = command.messageIds.map((messageId) =>
        roomReference.collection("messages").doc(messageId)
      );
      const receiptReferences = messageReferences.map((messageReference) =>
        messageReference.collection("receipts").doc(actorUid)
      );
      const [messageSnapshots, receiptSnapshots] = await Promise.all([
        Promise.all(messageReferences.map((reference) => transaction.get(reference))),
        Promise.all(receiptReferences.map((reference) => transaction.get(reference))),
      ]);
      const acknowledgedAt = Timestamp.now();
      messageSnapshots.forEach((messageSnapshot, index) => {
        const message = requireActiveMessage(messageSnapshot);
        if (message.senderUid === actorUid) return;
        const receiptSnapshot = receiptSnapshots[index];
        if (!receiptSnapshot) throw new HttpsError("internal", "Message receipt state is unavailable.");
        const wasDelivered = receiptSnapshot.get("deliveredAt") instanceof Timestamp;
        const wasRead = receiptSnapshot.get("readAt") instanceof Timestamp;
        const becomesDelivered = !wasDelivered;
        const becomesRead = command.read && !wasRead;
        if (!becomesDelivered && !becomesRead) return;
        transaction.set(receiptReferences[index]!, {
          actorUid,
          deliveredAt: wasDelivered ? receiptSnapshot.get("deliveredAt") : acknowledgedAt,
          readAt: command.read ? acknowledgedAt : receiptSnapshot.get("readAt") ?? null,
        }, {merge: true});
        transaction.update(messageReferenceAt(messageReferences, index), {
          deliveredToCount: message.deliveredToCount + (becomesDelivered ? 1 : 0),
          readByCount: message.readByCount + (becomesRead ? 1 : 0),
        });
        acknowledgedCount += 1;
      });
    });
    return {acknowledgedCount, read: command.read, roomId: command.roomId};
  },
);

async function reviseOwnMessage(
  actorUid: string,
  roomId: string,
  messageId: string,
  mutationId: string,
  expectedRevision: number,
  mutationName: "EDIT" | "DELETE",
  revision: (
    transaction: Transaction,
    messageSnapshot: DocumentSnapshot,
    message: ActiveMessageDocument,
    latestMessageId: string | null,
    roomReference: DocumentReference,
  ) => number,
  command: object,
): Promise<{messageId: string; revision: number; roomId: string}> {
  const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
  const messageReference = roomReference.collection("messages").doc(messageId);
  const receiptReference = firebaseAdminFirestore.doc(
    `messageMutationReceipts/${buildMessageMutationReceiptId(actorUid, mutationName, mutationId)}`,
  );
  const commandDigest = buildMutationCommandDigest(command);
  let resultingRevision = expectedRevision;
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const roomAuthorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
    const [messageSnapshot, receiptSnapshot] = await Promise.all([
      transaction.get(messageReference),
      transaction.get(receiptReference),
    ]);
    if (receiptSnapshot.exists) {
      if (
        receiptSnapshot.get("actorUid") !== actorUid ||
        receiptSnapshot.get("commandDigest") !== commandDigest ||
        receiptSnapshot.get("messageId") !== messageId ||
        receiptSnapshot.get("mutation") !== mutationName ||
        receiptSnapshot.get("roomId") !== roomId ||
        typeof receiptSnapshot.get("revision") !== "number"
      ) {
        throw new HttpsError("already-exists", "The mutation identifier was already used.");
      }
      resultingRevision = receiptSnapshot.get("revision") as number;
      return;
    }
    const message = requireActiveMessage(messageSnapshot);
    if (message.senderUid !== actorUid) {
      throw new HttpsError("permission-denied", "Only the sender can change this message.");
    }
    if (message.deletedAt !== null) {
      throw new HttpsError("failed-precondition", "The message is already deleted.");
    }
    if (message.revision !== expectedRevision) {
      throw new HttpsError("aborted", "The message changed before this mutation completed.");
    }
    resultingRevision = revision(
      transaction,
      messageSnapshot,
      message,
      roomAuthorization.latestMessageId,
      roomReference,
    );
    transaction.create(receiptReference, {
      actorUid,
      commandDigest,
      completedAt: Timestamp.now(),
      messageId,
      mutation: mutationName,
      revision: resultingRevision,
      roomId,
    });
  });
  return {messageId, revision: resultingRevision, roomId};
}

function requireIdempotentSend(
  snapshot: DocumentSnapshot,
  command: ReturnType<typeof parseSendRemoteMessageCommand>,
  actorUid: string,
) {
  const message = requireActiveMessage(snapshot);
  if (
    snapshot.get("authorKind") !== "HUMAN" ||
    snapshot.get("clientMessageId") !== command.messageId ||
    message.senderUid !== actorUid ||
    message.body !== command.body ||
    message.clientCreatedAt.toMillis() !== command.clientCreatedAtMillis ||
    message.replyToMessageId !== command.replyToMessageId
  ) {
    throw new HttpsError("already-exists", "The message identifier was already used.");
  }
}

function requireActiveMessage(snapshot: DocumentSnapshot): ActiveMessageDocument {
  if (!snapshot.exists) throw new HttpsError("not-found", "The message was not found.");
  const body = snapshot.get("body");
  const clientCreatedAt = snapshot.get("clientCreatedAt");
  const deliveredToCount = snapshot.get("deliveredToCount") ?? 0;
  const deletedAt = snapshot.get("deletedAt");
  const reactionCounts = readReactionCounts(snapshot.get("reactionCounts") ?? {});
  const readByCount = snapshot.get("readByCount") ?? 0;
  const replyToMessageId = snapshot.get("replyToMessageId");
  const revision = snapshot.get("revision") ?? 1;
  const senderUid = snapshot.get("senderUid");
  if (
    typeof body !== "string" ||
    !(clientCreatedAt instanceof Timestamp) ||
    typeof deliveredToCount !== "number" ||
    !Number.isSafeInteger(deliveredToCount) ||
    deliveredToCount < 0 ||
    (deletedAt !== null && !(deletedAt instanceof Timestamp)) ||
    typeof readByCount !== "number" ||
    !Number.isSafeInteger(readByCount) ||
    readByCount < 0 ||
    (replyToMessageId !== null && typeof replyToMessageId !== "string") ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 1 ||
    typeof senderUid !== "string" ||
    reactionCounts === null
  ) {
    throw new HttpsError("data-loss", "Message state is malformed.");
  }
  return {
    body,
    clientCreatedAt,
    deliveredToCount,
    deletedAt,
    reactionCounts,
    readByCount,
    replyToMessageId,
    revision,
    senderUid,
  };
}

function readReactionCounts(value: unknown): Record<string, number> | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const entries = Object.entries(value);
  if (
    entries.length > MAXIMUM_REACTION_TYPES ||
    entries.some(([emoji, count]) =>
      emoji.length === 0 ||
      emoji.length > 16 ||
      !Number.isSafeInteger(count) ||
      (count as number) < 1
    )
  ) {
    return null;
  }
  return Object.fromEntries(entries) as Record<string, number>;
}

function messageReferenceAt(
  references: DocumentReference[],
  index: number,
): DocumentReference {
  const reference = references[index];
  if (!reference) throw new HttpsError("internal", "Message reference is unavailable.");
  return reference;
}

const MAXIMUM_REACTION_TYPES = 32;
