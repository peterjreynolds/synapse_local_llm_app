import {DocumentSnapshot, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {
  buildCinderResponseMessageId,
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_ASSISTANT_ROOM_ID,
  CINDER_PARTICIPANT_ID,
  cinderTimestampSequence,
  CompleteCinderResponseCommand,
  digestCinderResponseBody,
} from "./cinderDomain.js";
import {
  CinderJobDocument,
  cinderAuditReference,
  cinderConversationReference,
  cinderJobReference,
  requireClaimedCinderJob,
  requireIdempotentCinderTerminalAudit,
  terminalCinderAuditFields,
} from "./cinderJob.js";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CinderCompletionReceipt {
  completionState: "COMPLETE" | "SKIPPED";
  messageId: string | null;
  reason: "PARTICIPANT_REMOVED" | "SOURCE_UNAVAILABLE" | null;
  roomId: string;
  sequence: number | null;
}

export async function completeCinderResponse(
  command: CompleteCinderResponseCommand,
): Promise<CinderCompletionReceipt> {
  const jobReference = cinderJobReference(command.jobId);
  const auditReference = cinderAuditReference(command.jobId);
  const responseBodyDigest = digestCinderResponseBody(command.body);
  let receipt: CinderCompletionReceipt | null = null;
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const [jobSnapshot, auditSnapshot] = await Promise.all([
      transaction.get(jobReference),
      transaction.get(auditReference),
    ]);
    if (!jobSnapshot.exists) {
      receipt = readIdempotentCompletionReceipt(
        auditSnapshot,
        command,
        responseBodyDigest,
      );
      return;
    }
    const completedAt = Timestamp.now();
    const job = requireClaimedCinderJob(jobSnapshot, command, completedAt.toMillis());
    receipt = job.roomKind === "ASSISTANT" ?
      await completeAssistantResponse(
        transaction,
        command.jobId,
        job,
        command.body,
        responseBodyDigest,
        completedAt,
      ) :
      await completeHumanRoomResponse(
        transaction,
        command.jobId,
        job,
        command.body,
        responseBodyDigest,
        completedAt,
      );
    transaction.delete(jobReference);
  });
  if (receipt === null) throw new HttpsError("internal", "Cinder completion receipt is unavailable.");
  return receipt;
}

async function completeAssistantResponse(
  transaction: Transaction,
  jobId: string,
  job: CinderJobDocument,
  body: string,
  responseBodyDigest: string,
  completedAt: Timestamp,
): Promise<CinderCompletionReceipt> {
  const conversationReference = cinderConversationReference(job.accountUid);
  const sourceReference = conversationReference.collection("messages").doc(job.sourceMessageId);
  const messageId = buildCinderResponseMessageId(jobId);
  const responseReference = conversationReference.collection("messages").doc(messageId);
  const auditReference = cinderAuditReference(jobId);
  const [conversationSnapshot, sourceSnapshot, responseSnapshot] = await Promise.all([
    transaction.get(conversationReference),
    transaction.get(sourceReference),
    transaction.get(responseReference),
  ]);
  const conversation = readAssistantConversationForCompletion(conversationSnapshot, job);
  requireAssistantSourceMessage(sourceSnapshot, job);
  if (responseSnapshot.exists) {
    throw new HttpsError("data-loss", "A Cinder response exists without its terminal receipt.");
  }
  const sequence = conversation.lastSequence + 1;
  transaction.create(responseReference, {
    accountUid: job.accountUid,
    aiParticipantId: CINDER_PARTICIPANT_ID,
    aiProvenance: CINDER_AI_PROVENANCE,
    aiProvider: CINDER_AI_PROVIDER,
    assistantId: CINDER_ASSISTANT_ID,
    attachmentIds: [],
    attachments: [],
    authorKind: "REMOTE_AI",
    body,
    clientCreatedAt: completedAt,
    clientMessageId: messageId,
    contentDigest: responseBodyDigest,
    createdAt: completedAt,
    deletedAt: null,
    editedAt: null,
    idempotencyKey: messageId,
    replyToMessageId: job.sourceMessageId,
    revision: 1,
    roomId: CINDER_ASSISTANT_ROOM_ID,
    senderUid: CINDER_PARTICIPANT_ID,
    sequence,
    sourceMessageId: job.sourceMessageId,
  });
  transaction.update(conversationReference, {
    lastSequence: sequence,
    latestMessage: {
      body,
      createdAt: completedAt,
      messageId,
      senderUid: CINDER_PARTICIPANT_ID,
      sequence,
    },
    revision: conversation.revision + 1,
    updatedAt: completedAt,
  });
  transaction.create(auditReference, {
    ...terminalCinderAuditFields(jobId, job, "COMPLETE", completedAt),
    messageId,
    responseBodyDigest,
    sequence,
  });
  return {
    completionState: "COMPLETE",
    messageId,
    reason: null,
    roomId: CINDER_ASSISTANT_ROOM_ID,
    sequence,
  };
}

async function completeHumanRoomResponse(
  transaction: Transaction,
  jobId: string,
  job: CinderJobDocument,
  body: string,
  responseBodyDigest: string,
  completedAt: Timestamp,
): Promise<CinderCompletionReceipt> {
  const roomReference = firebaseAdminFirestore.doc(`rooms/${job.roomId}`);
  const participantReference = roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID);
  const sourceReference = roomReference.collection("messages").doc(job.sourceMessageId);
  const messageId = buildCinderResponseMessageId(jobId);
  const responseReference = roomReference.collection("messages").doc(messageId);
  const auditReference = cinderAuditReference(jobId);
  const [roomSnapshot, participantSnapshot, sourceSnapshot, responseSnapshot] = await Promise.all([
    transaction.get(roomReference),
    transaction.get(participantReference),
    transaction.get(sourceReference),
    transaction.get(responseReference),
  ]);
  const unavailableReason = humanRoomCompletionUnavailableReason(
    roomSnapshot,
    participantSnapshot,
    sourceSnapshot,
    job,
  );
  if (unavailableReason !== null) {
    transaction.create(auditReference, {
      ...terminalCinderAuditFields(jobId, job, "SKIPPED", completedAt),
      reason: unavailableReason,
      responseBodyDigest,
    });
    return {
      completionState: "SKIPPED",
      messageId: null,
      reason: unavailableReason,
      roomId: job.roomId,
      sequence: null,
    };
  }
  if (responseSnapshot.exists) {
    throw new HttpsError("data-loss", "A Cinder response exists without its terminal receipt.");
  }
  const serverSequence = cinderTimestampSequence(completedAt.seconds, completedAt.nanoseconds);
  transaction.create(responseReference, {
    aiParticipantId: CINDER_PARTICIPANT_ID,
    aiProvenance: CINDER_AI_PROVENANCE,
    aiProvider: CINDER_AI_PROVIDER,
    aiResponseJobId: jobId,
    assistantId: CINDER_ASSISTANT_ID,
    attachmentIds: [],
    attachments: [],
    authorKind: "REMOTE_AI",
    body,
    clientCreatedAt: completedAt,
    clientMessageId: messageId,
    createdAt: completedAt,
    deletedAt: null,
    deliveredToCount: 0,
    editedAt: null,
    reactionCounts: {},
    readByCount: 0,
    replyToMessageId: job.sourceMessageId,
    revision: 1,
    senderUid: CINDER_PARTICIPANT_ID,
    serverSequence,
    sourceMessageId: job.sourceMessageId,
  });
  transaction.update(roomReference, {
    latestMessage: {
      body,
      createdAt: completedAt,
      messageId,
      senderUid: CINDER_PARTICIPANT_ID,
    },
    updatedAt: completedAt,
  });
  transaction.create(auditReference, {
    ...terminalCinderAuditFields(jobId, job, "COMPLETE", completedAt),
    messageId,
    responseBodyDigest,
    sequence: serverSequence,
  });
  return {
    completionState: "COMPLETE",
    messageId,
    reason: null,
    roomId: job.roomId,
    sequence: serverSequence,
  };
}

function readAssistantConversationForCompletion(
  snapshot: DocumentSnapshot,
  job: CinderJobDocument,
): {lastSequence: number; revision: number} {
  const lastSequence = snapshot.get("lastSequence");
  const revision = snapshot.get("revision");
  if (
    !snapshot.exists ||
    snapshot.id !== job.accountUid ||
    snapshot.get("accountUid") !== job.accountUid ||
    snapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    snapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    snapshot.get("roomId") !== CINDER_ASSISTANT_ROOM_ID ||
    typeof lastSequence !== "number" ||
    !Number.isSafeInteger(lastSequence) ||
    lastSequence < job.sourceSequence ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 1
  ) {
    throw new HttpsError("data-loss", "Cinder conversation state is malformed.");
  }
  return {lastSequence, revision};
}

function requireAssistantSourceMessage(
  snapshot: DocumentSnapshot,
  job: CinderJobDocument,
): void {
  if (
    !snapshot.exists ||
    snapshot.get("authorKind") !== "HUMAN" ||
    snapshot.get("senderUid") !== job.accountUid ||
    snapshot.get("contentDigest") !== job.contentDigest ||
    snapshot.get("deletedAt") !== null
  ) {
    throw new HttpsError("failed-precondition", "The Cinder source message is unavailable.");
  }
}

function humanRoomCompletionUnavailableReason(
  roomSnapshot: DocumentSnapshot,
  participantSnapshot: DocumentSnapshot,
  sourceSnapshot: DocumentSnapshot,
  job: CinderJobDocument,
): "PARTICIPANT_REMOVED" | "SOURCE_UNAVAILABLE" | null {
  if (
    !roomSnapshot.exists ||
    roomSnapshot.get("deletedAt") !== null ||
    roomSnapshot.get("kind") !== job.roomKind ||
    !sourceSnapshot.exists ||
    sourceSnapshot.get("deletedAt") !== null ||
    sourceSnapshot.get("authorKind") !== "HUMAN" ||
    sourceSnapshot.get("senderUid") !== job.sourceSenderUid
  ) {
    return "SOURCE_UNAVAILABLE";
  }
  if (
    !participantSnapshot.exists ||
    participantSnapshot.get("active") !== true ||
    participantSnapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    participantSnapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    participantSnapshot.get("provenance") !== CINDER_AI_PROVENANCE ||
    participantSnapshot.get("provider") !== CINDER_AI_PROVIDER
  ) {
    return "PARTICIPANT_REMOVED";
  }
  return null;
}

function readIdempotentCompletionReceipt(
  auditSnapshot: DocumentSnapshot,
  command: CompleteCinderResponseCommand,
  responseBodyDigest: string,
): CinderCompletionReceipt {
  const completionState = auditSnapshot.get("completionState");
  if (completionState !== "COMPLETE" && completionState !== "SKIPPED") {
    throw new HttpsError("failed-precondition", "The Cinder response lease is unavailable.");
  }
  requireIdempotentCinderTerminalAudit(auditSnapshot, command, completionState);
  if (auditSnapshot.get("responseBodyDigest") !== responseBodyDigest) {
    throw new HttpsError(
      "already-exists",
      "The Cinder response job already completed with different content.",
    );
  }
  const roomId = auditSnapshot.get("roomId");
  const messageId = auditSnapshot.get("messageId") ?? null;
  const sequence = auditSnapshot.get("sequence") ?? null;
  const reason = auditSnapshot.get("reason") ?? null;
  if (
    typeof roomId !== "string" ||
    (messageId !== null && typeof messageId !== "string") ||
    (sequence !== null && (typeof sequence !== "number" || !Number.isSafeInteger(sequence))) ||
    (reason !== null && reason !== "PARTICIPANT_REMOVED" && reason !== "SOURCE_UNAVAILABLE") ||
    (completionState === "COMPLETE" && (messageId === null || sequence === null || reason !== null)) ||
    (completionState === "SKIPPED" && (messageId !== null || sequence !== null || reason === null))
  ) {
    throw new HttpsError("data-loss", "Cinder completion receipt is malformed.");
  }
  return {
    completionState,
    messageId: messageId as string | null,
    reason: reason as "PARTICIPANT_REMOVED" | "SOURCE_UNAVAILABLE" | null,
    roomId,
    sequence: sequence as number | null,
  };
}
