import {DocumentSnapshot, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {
  buildCinderOutboundMessageId,
  cinderModeAllowsProactiveMessage,
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_ASSISTANT_ROOM_ID,
  CINDER_PARTICIPANT_ID,
  cinderTimestampSequence,
  digestCinderOutboundMessage,
  digestCinderResponseBody,
  resolveStoredCinderParticipationMode,
  SendCinderOutboundMessageCommand,
} from "./cinderDomain.js";
import {cinderConversationReference} from "./cinderJob.js";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CinderOutboundReceipt {
  messageId: string;
  roomId: string;
  sequence: number;
}

export async function sendCinderOutbound(
  command: SendCinderOutboundMessageCommand,
): Promise<CinderOutboundReceipt> {
  const messageId = buildCinderOutboundMessageId(command.idempotencyKey);
  const commandDigest = digestCinderOutboundMessage(command);
  const sentAt = Timestamp.now();
  return command.roomId === CINDER_ASSISTANT_ROOM_ID ?
    sendAssistantOutboundMessage(command, messageId, commandDigest, sentAt) :
    sendHumanRoomOutboundMessage(command, messageId, commandDigest, sentAt);
}

async function sendAssistantOutboundMessage(
  command: SendCinderOutboundMessageCommand,
  messageId: string,
  commandDigest: string,
  sentAt: Timestamp,
): Promise<CinderOutboundReceipt> {
  const profileReference = firebaseAdminFirestore.doc(`profiles/${command.accountUid}`);
  const conversationReference = cinderConversationReference(command.accountUid);
  const messageReference = conversationReference.collection("messages").doc(messageId);
  let receipt: CinderOutboundReceipt | null = null;
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const [profileSnapshot, conversationSnapshot, messageSnapshot] = await Promise.all([
      transaction.get(profileReference),
      transaction.get(conversationReference),
      transaction.get(messageReference),
    ]);
    const conversation = readAssistantConversation(conversationSnapshot, command.accountUid);
    if (messageSnapshot.exists) {
      receipt = readIdempotentOutboundReceipt(
        messageSnapshot,
        command,
        messageId,
        commandDigest,
        "sequence",
      );
      if (conversation.lastSequence < receipt.sequence) malformedOutboundState();
      return;
    }
    requireActiveOutboundAccount(profileSnapshot);
    const sequence = conversation.lastSequence + 1;
    const messageSummary = buildLatestMessageSummary(command.body, messageId, sequence, sentAt);
    transaction.create(messageReference, {
      accountUid: command.accountUid,
      aiParticipantId: CINDER_PARTICIPANT_ID,
      aiProvenance: CINDER_AI_PROVENANCE,
      aiProvider: CINDER_AI_PROVIDER,
      assistantId: CINDER_ASSISTANT_ID,
      attachmentIds: [],
      attachments: [],
      authorKind: "REMOTE_AI",
      body: command.body,
      cinderOutboundCommandDigest: commandDigest,
      clientCreatedAt: sentAt,
      clientMessageId: messageId,
      contentDigest: digestCinderResponseBody(command.body),
      createdAt: sentAt,
      deletedAt: null,
      editedAt: null,
      idempotencyKey: command.idempotencyKey,
      replyToMessageId: null,
      revision: 1,
      roomId: CINDER_ASSISTANT_ROOM_ID,
      senderUid: CINDER_PARTICIPANT_ID,
      sequence,
      sourceMessageId: null,
    });
    if (conversationSnapshot.exists) {
      transaction.update(conversationReference, {
        lastSequence: sequence,
        latestMessage: messageSummary,
        revision: conversation.revision + 1,
        updatedAt: sentAt,
      });
    } else {
      transaction.create(conversationReference, {
        accountUid: command.accountUid,
        assistantId: CINDER_ASSISTANT_ID,
        createdAt: sentAt,
        lastSequence: sequence,
        latestMessage: messageSummary,
        participantId: CINDER_PARTICIPANT_ID,
        revision: 1,
        roomId: CINDER_ASSISTANT_ROOM_ID,
        updatedAt: sentAt,
      });
    }
    receipt = {messageId, roomId: CINDER_ASSISTANT_ROOM_ID, sequence};
  });
  return requireOutboundReceipt(receipt);
}

async function sendHumanRoomOutboundMessage(
  command: SendCinderOutboundMessageCommand,
  messageId: string,
  commandDigest: string,
  sentAt: Timestamp,
): Promise<CinderOutboundReceipt> {
  const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
  const messageReference = roomReference.collection("messages").doc(messageId);
  const participantReference = roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID);
  const membershipReference = roomReference.collection("members").doc(command.accountUid);
  const profileReference = firebaseAdminFirestore.doc(`profiles/${command.accountUid}`);
  let receipt: CinderOutboundReceipt | null = null;
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const [roomSnapshot, participantSnapshot, membershipSnapshot, profileSnapshot, messageSnapshot] =
      await Promise.all([
        transaction.get(roomReference),
        transaction.get(participantReference),
        transaction.get(membershipReference),
        transaction.get(profileReference),
        transaction.get(messageReference),
      ]);
    if (messageSnapshot.exists) {
      receipt = readIdempotentOutboundReceipt(
        messageSnapshot,
        command,
        messageId,
        commandDigest,
        "serverSequence",
      );
      return;
    }
    requireActiveHumanRoomTarget(
      command,
      roomSnapshot,
      participantSnapshot,
      membershipSnapshot,
      profileSnapshot,
    );
    const serverSequence = cinderTimestampSequence(sentAt.seconds, sentAt.nanoseconds);
    transaction.create(messageReference, {
      aiParticipantId: CINDER_PARTICIPANT_ID,
      aiProvenance: CINDER_AI_PROVENANCE,
      aiProvider: CINDER_AI_PROVIDER,
      assistantId: CINDER_ASSISTANT_ID,
      attachmentIds: [],
      attachments: [],
      authorKind: "REMOTE_AI",
      body: command.body,
      cinderOutboundCommandDigest: commandDigest,
      clientCreatedAt: sentAt,
      clientMessageId: messageId,
      createdAt: sentAt,
      deletedAt: null,
      deliveredToCount: 0,
      editedAt: null,
      reactionCounts: {},
      readByCount: 0,
      replyToMessageId: null,
      revision: 1,
      senderUid: CINDER_PARTICIPANT_ID,
      serverSequence,
      sourceMessageId: null,
    });
    transaction.update(roomReference, {
      latestMessage: buildLatestMessageSummary(command.body, messageId, null, sentAt),
      updatedAt: sentAt,
    });
    receipt = {messageId, roomId: command.roomId, sequence: serverSequence};
  });
  return requireOutboundReceipt(receipt);
}

function requireActiveHumanRoomTarget(
  command: SendCinderOutboundMessageCommand,
  roomSnapshot: DocumentSnapshot,
  participantSnapshot: DocumentSnapshot,
  membershipSnapshot: DocumentSnapshot,
  profileSnapshot: DocumentSnapshot,
): void {
  const expectedRoomKind = command.roomId.startsWith("direct_") ? "DIRECT" : "GROUP";
  const activeMemberIds = roomSnapshot.get("activeMemberIds");
  const participationMode = resolveStoredCinderParticipationMode({
    mode: participantSnapshot.get("mode"),
    responsePolicy: participantSnapshot.get("responsePolicy"),
  });
  if (
    !roomSnapshot.exists ||
    roomSnapshot.get("deletedAt") !== null ||
    roomSnapshot.get("kind") !== expectedRoomKind ||
    !Array.isArray(activeMemberIds) ||
    !activeMemberIds.includes(command.accountUid) ||
    !membershipSnapshot.exists ||
    membershipSnapshot.get("active") !== true ||
    !participantSnapshot.exists ||
    participantSnapshot.get("active") !== true ||
    participantSnapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    participantSnapshot.get("kind") !== "REMOTE_AI" ||
    participantSnapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    participantSnapshot.get("provenance") !== CINDER_AI_PROVENANCE ||
    participantSnapshot.get("provider") !== CINDER_AI_PROVIDER ||
    participationMode === null ||
    !cinderModeAllowsProactiveMessage(participationMode)
  ) {
    outboundTargetUnavailable();
  }
  requireActiveOutboundAccount(profileSnapshot);
}

function requireActiveOutboundAccount(profileSnapshot: DocumentSnapshot): void {
  if (
    !profileSnapshot.exists ||
    profileSnapshot.get("allowed") !== true ||
    profileSnapshot.get("accountState") !== "ACTIVE" ||
    profileSnapshot.get("mustChangePassword") !== false
  ) {
    outboundTargetUnavailable();
  }
}

function readAssistantConversation(
  snapshot: DocumentSnapshot,
  accountUid: string,
): {lastSequence: number; revision: number} {
  if (!snapshot.exists) return {lastSequence: 0, revision: 0};
  const lastSequence = snapshot.get("lastSequence");
  const revision = snapshot.get("revision");
  if (
    snapshot.id !== accountUid ||
    snapshot.get("accountUid") !== accountUid ||
    snapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    snapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    snapshot.get("roomId") !== CINDER_ASSISTANT_ROOM_ID ||
    typeof lastSequence !== "number" ||
    !Number.isSafeInteger(lastSequence) ||
    lastSequence < 0 ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 0
  ) {
    malformedOutboundState();
  }
  return {lastSequence, revision};
}

function readIdempotentOutboundReceipt(
  snapshot: DocumentSnapshot,
  command: SendCinderOutboundMessageCommand,
  messageId: string,
  commandDigest: string,
  sequenceField: "sequence" | "serverSequence",
): CinderOutboundReceipt {
  if (snapshot.get("cinderOutboundCommandDigest") !== commandDigest) {
    throw new HttpsError(
      "already-exists",
      "The Cinder outbound idempotency key was already used with different command data.",
    );
  }
  const sequence = snapshot.get(sequenceField);
  if (
    snapshot.id !== messageId ||
    snapshot.get("clientMessageId") !== messageId ||
    snapshot.get("authorKind") !== "REMOTE_AI" ||
    snapshot.get("senderUid") !== CINDER_PARTICIPANT_ID ||
    snapshot.get("aiParticipantId") !== CINDER_PARTICIPANT_ID ||
    snapshot.get("aiProvenance") !== CINDER_AI_PROVENANCE ||
    snapshot.get("aiProvider") !== CINDER_AI_PROVIDER ||
    snapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    snapshot.get("body") !== command.body ||
    snapshot.get("deletedAt") !== null ||
    typeof sequence !== "number" ||
    !Number.isSafeInteger(sequence) ||
    sequence < 1
  ) {
    malformedOutboundState();
  }
  return {messageId, roomId: command.roomId, sequence};
}

function buildLatestMessageSummary(
  body: string,
  messageId: string,
  sequence: number | null,
  createdAt: Timestamp,
): Record<string, unknown> {
  return {
    body,
    createdAt,
    messageId,
    senderUid: CINDER_PARTICIPANT_ID,
    ...(sequence === null ? {} : {sequence}),
  };
}

function requireOutboundReceipt(receipt: CinderOutboundReceipt | null): CinderOutboundReceipt {
  if (receipt === null) throw new HttpsError("internal", "Cinder outbound receipt is unavailable.");
  return receipt;
}

function outboundTargetUnavailable(): never {
  throw new HttpsError("not-found", "The Cinder outbound target is unavailable.");
}

function malformedOutboundState(): never {
  throw new HttpsError("data-loss", "Cinder outbound state is malformed.");
}
