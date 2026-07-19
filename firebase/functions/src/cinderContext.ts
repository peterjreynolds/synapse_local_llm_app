import {DocumentSnapshot, Timestamp} from "firebase-admin/firestore";
import {
  CINDER_PARTICIPANT_ID,
  cinderTimestampSequence,
  isTrustedCinderRemoteAiMessage,
  MAXIMUM_CINDER_CONTEXT_MESSAGES,
} from "./cinderDomain.js";
import {readCinderMessageSyncRecord} from "./cinderConversation.js";
import {CinderJobDocument, cinderConversationReference} from "./cinderJob.js";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CinderClaimContextMessage {
  aiParticipantId: string | null;
  authorKind: "HUMAN" | "SYNAPSE_AI" | "REMOTE_AI";
  body: string;
  messageId: string;
  revision: number;
  senderDisplayName: string;
  senderUid: string;
  sequence: number;
  serverCreatedAtMillis: number;
}

export interface CinderJobContext {
  recentMessages: CinderClaimContextMessage[];
  sourceMessage: CinderClaimContextMessage;
}

export async function loadCinderJobContext(job: CinderJobDocument): Promise<CinderJobContext> {
  const recentMessages = job.roomKind === "ASSISTANT" ?
    await loadAssistantContext(job) :
    await loadHumanRoomContext(job);
  const sourceMessage: CinderClaimContextMessage = {
    aiParticipantId: null,
    authorKind: "HUMAN",
    body: job.sourceBody,
    messageId: job.sourceMessageId,
    revision: job.sourceRevision,
    senderDisplayName: job.sourceSenderDisplayName,
    senderUid: job.sourceSenderUid,
    sequence: job.sourceSequence,
    serverCreatedAtMillis: job.sourceServerCreatedAt.toMillis(),
  };
  const sourceIndex = recentMessages.findIndex((message) => message.messageId === job.sourceMessageId);
  if (sourceIndex >= 0) recentMessages[sourceIndex] = sourceMessage;
  else recentMessages.push(sourceMessage);
  return {
    recentMessages: recentMessages
      .sort(compareContextMessages)
      .slice(-MAXIMUM_CINDER_CONTEXT_MESSAGES),
    sourceMessage,
  };
}

async function loadAssistantContext(job: CinderJobDocument): Promise<CinderClaimContextMessage[]> {
  const snapshot = await cinderConversationReference(job.accountUid)
    .collection("messages")
    .where("sequence", "<=", job.sourceSequence)
    .orderBy("sequence", "desc")
    .limit(MAXIMUM_CINDER_CONTEXT_MESSAGES)
    .get();
  return snapshot.docs.map((messageSnapshot) => {
    const message = readCinderMessageSyncRecord(messageSnapshot, job.accountUid);
    return {
      aiParticipantId: message.aiParticipantId,
      authorKind: message.authorKind,
      body: message.body,
      messageId: message.messageId,
      revision: message.revision,
      senderDisplayName: message.authorKind === "REMOTE_AI" ? "Cinder" :
        message.senderUid === job.sourceSenderUid ? job.sourceSenderDisplayName : "Human",
      senderUid: message.senderUid,
      sequence: message.sequence,
      serverCreatedAtMillis: message.createdAtMillis,
    };
  });
}

async function loadHumanRoomContext(job: CinderJobDocument): Promise<CinderClaimContextMessage[]> {
  const roomReference = firebaseAdminFirestore.doc(`rooms/${job.roomId}`);
  const snapshot = await roomReference.collection("messages")
    .where("createdAt", "<=", job.sourceServerCreatedAt)
    .orderBy("createdAt", "desc")
    .limit(MAXIMUM_CINDER_CONTEXT_MESSAGES)
    .get();
  const parsedMessages = snapshot.docs.map(readHumanRoomContextMessage).filter(isPresent);
  const humanSenderUids = [...new Set(
    parsedMessages
      .filter((message) => message.authorKind === "HUMAN")
      .map((message) => message.senderUid),
  )];
  const profileSnapshots = humanSenderUids.length === 0 ? [] : await firebaseAdminFirestore.getAll(
    ...humanSenderUids.map((uid) => firebaseAdminFirestore.doc(`profiles/${uid}`)),
  );
  const displayNames = new Map<string, string>();
  profileSnapshots.forEach((profileSnapshot) => {
    const displayName = profileSnapshot.get("displayName");
    if (typeof displayName === "string" && displayName.length > 0 && displayName.length <= 64) {
      displayNames.set(profileSnapshot.id, displayName);
    }
  });
  displayNames.set(job.sourceSenderUid, job.sourceSenderDisplayName);
  return parsedMessages.map((message) => ({
    ...message,
    senderDisplayName: message.authorKind === "REMOTE_AI" ? "Cinder" :
      message.authorKind === "SYNAPSE_AI" ? "Synapse" :
        displayNames.get(message.senderUid) ?? "Human",
  }));
}

function readHumanRoomContextMessage(
  snapshot: DocumentSnapshot,
): Omit<CinderClaimContextMessage, "senderDisplayName"> | null {
  if (!snapshot.exists || snapshot.get("deletedAt") !== null) return null;
  const authorKind = snapshot.get("authorKind");
  const body = snapshot.get("body");
  const senderUid = snapshot.get("senderUid");
  const createdAt = snapshot.get("createdAt");
  const revision = snapshot.get("revision") ?? 1;
  const localAiAttributionIsValid =
    authorKind === "SYNAPSE_AI" &&
    senderUid === "participant-synapse-local-ai" &&
    snapshot.get("aiParticipantId") === "participant-synapse-local-ai" &&
    (snapshot.get("aiProvenance") === "PHONE_LOCAL" || snapshot.get("aiProvenance") === "REMOTE_HOSTED");
  const remoteAiAttributionIsValid = isTrustedCinderRemoteAiMessage({
    aiParticipantId: snapshot.get("aiParticipantId"),
    aiProvenance: snapshot.get("aiProvenance"),
    aiProvider: snapshot.get("aiProvider"),
    assistantId: snapshot.get("assistantId"),
    authorKind,
    senderUid,
  });
  if (
    (authorKind !== "HUMAN" && !localAiAttributionIsValid && !remoteAiAttributionIsValid) ||
    typeof body !== "string" ||
    body.length === 0 ||
    body.length > 4_000 ||
    typeof senderUid !== "string" ||
    !(createdAt instanceof Timestamp) ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 1
  ) {
    return null;
  }
  const narrowedAuthorKind: "HUMAN" | "SYNAPSE_AI" | "REMOTE_AI" =
    authorKind === "SYNAPSE_AI" ? "SYNAPSE_AI" :
      authorKind === "REMOTE_AI" ? "REMOTE_AI" : "HUMAN";
  return {
    aiParticipantId: narrowedAuthorKind === "HUMAN" ? null :
      narrowedAuthorKind === "REMOTE_AI" ? CINDER_PARTICIPANT_ID : "participant-synapse-local-ai",
    authorKind: narrowedAuthorKind,
    body,
    messageId: snapshot.id,
    revision,
    senderUid,
    sequence: cinderTimestampSequence(createdAt.seconds, createdAt.nanoseconds),
    serverCreatedAtMillis: createdAt.toMillis(),
  };
}

function compareContextMessages(
  first: CinderClaimContextMessage,
  second: CinderClaimContextMessage,
): number {
  return first.sequence - second.sequence || first.messageId.localeCompare(second.messageId);
}

function isPresent<T>(value: T | null): value is T {
  return value !== null;
}
