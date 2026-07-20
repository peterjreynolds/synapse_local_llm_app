import {DocumentSnapshot, Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {enforceCallableRateLimit} from "./callableRateLimit.js";
import {
  buildCinderDirectContentDigest,
  buildCinderJobId,
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_ASSISTANT_ROOM_ID,
  CINDER_LEGACY_RESPONSE_POLICY,
  CINDER_PARTICIPANT_ID,
  CINDER_WORKER_PROTOCOL_VERSION,
  CinderWorkState,
  isCinderWorkerAvailable,
  isTrustedCinderRemoteAiMessage,
  parseSubmitCinderMessageCommand,
  parseSyncCinderMessagesCommand,
  resolveCinderWorkState,
} from "./cinderDomain.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CinderAvailabilityReceipt {
  available: boolean;
  availableUntilMillis: number | null;
  checkedAtMillis: number;
  protocolVersion: typeof CINDER_WORKER_PROTOCOL_VERSION;
  workState: CinderWorkState;
}

export interface CinderMessageSyncRecord {
  aiParticipantId: string | null;
  aiProvenance: string | null;
  aiProvider: string | null;
  assistantId: typeof CINDER_ASSISTANT_ID;
  authorKind: "HUMAN" | "REMOTE_AI";
  body: string;
  clientCreatedAtMillis: number;
  createdAtMillis: number;
  idempotencyKey: string;
  messageId: string;
  revision: number;
  roomId: typeof CINDER_ASSISTANT_ROOM_ID;
  senderUid: string;
  sequence: number;
  sourceMessageId: string | null;
}

export const getCinderAvailability = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<CinderAvailabilityReceipt> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(actorUid, "cinderAvailabilityPolling");
    const checkedAt = Timestamp.now();
    const [statusSnapshot, jobsSnapshot] = await Promise.all([
      cinderWorkerStatusReference().get(),
      firebaseAdminFirestore.collection("cinderResponseJobs")
        .where("accountUid", "==", actorUid)
        .limit(10)
        .get(),
    ]);
    return readCinderAvailabilityReceipt(
      statusSnapshot,
      checkedAt.toMillis(),
      resolveCinderWorkState(
        jobsSnapshot.docs
          .filter((job) => job.get("roomKind") === "ASSISTANT")
          .map((job) => job.get("state")),
      ),
    );
  },
);

export const submitCinderMessage = onCall(
  {region: FIREBASE_FUNCTIONS_REGION, timeoutSeconds: 30},
  async (request): Promise<{
    acceptance: "ACCEPTED" | "ALREADY_ACCEPTED";
    messageId: string;
    revision: number;
    roomId: typeof CINDER_ASSISTANT_ROOM_ID;
    sequence: number;
  }> => {
    const account = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(account.uid, "conversationMutation");
    const command = parseSubmitCinderMessageCommand(request.data);
    const sourceSenderDisplayName = account.profile.displayName;
    if (sourceSenderDisplayName.length === 0 || sourceSenderDisplayName.length > 64) {
      throw new HttpsError("data-loss", "The active account profile is malformed.");
    }
    const contentDigest = buildCinderDirectContentDigest(account.uid, command);
    const conversationReference = cinderConversationReference(account.uid);
    const messageReference = conversationReference.collection("messages").doc(command.messageId);
    const jobId = buildCinderJobId({
      accountUid: account.uid,
      roomId: command.roomId,
      roomKind: "ASSISTANT",
      sourceMessageId: command.idempotencyKey,
    });
    const jobReference = firebaseAdminFirestore.doc(`cinderResponseJobs/${jobId}`);
    const auditReference = firebaseAdminFirestore.doc(`cinderResponseAudits/${jobId}`);
    const statusReference = cinderWorkerStatusReference();
    let acceptance: "ACCEPTED" | "ALREADY_ACCEPTED" = "ACCEPTED";
    let sequence = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [conversationSnapshot, messageSnapshot, jobSnapshot, auditSnapshot, statusSnapshot] =
        await Promise.all([
          transaction.get(conversationReference),
          transaction.get(messageReference),
          transaction.get(jobReference),
          transaction.get(auditReference),
          transaction.get(statusReference),
      ]);
      const acceptedAt = Timestamp.now();
      if (messageSnapshot.exists) {
        requireIdempotentDirectSubmission(
          messageSnapshot,
          account.uid,
          command.messageId,
          command.idempotencyKey,
          contentDigest,
        );
        if (!jobSnapshot.exists && !auditSnapshot.exists) {
          throw new HttpsError("data-loss", "Cinder submission state is incomplete.");
        }
        requireMatchingJobOrAudit(jobSnapshot, auditSnapshot, contentDigest, command.messageId);
        sequence = requirePositiveSafeInteger(messageSnapshot.get("sequence"), "message sequence");
        acceptance = "ALREADY_ACCEPTED";
        return;
      }
      if (jobSnapshot.exists || auditSnapshot.exists) {
        requireMatchingJobOrAudit(jobSnapshot, auditSnapshot, contentDigest, command.messageId);
        throw new HttpsError(
          "already-exists",
          "The Cinder idempotency key was already used by another message.",
        );
      }
      const availability = readCinderAvailabilityReceipt(statusSnapshot, acceptedAt.toMillis());
      if (!availability.available) {
        throw new HttpsError(
          "failed-precondition",
          "Cinder is offline right now. Your draft was not submitted.",
        );
      }
      const conversation = readCinderConversation(conversationSnapshot, account.uid);
      sequence = conversation.lastSequence + 1;
      const revision = conversation.revision + 1;
      transaction.set(conversationReference, {
        accountUid: account.uid,
        assistantId: CINDER_ASSISTANT_ID,
        createdAt: conversation.createdAt ?? acceptedAt,
        lastSequence: sequence,
        latestMessage: {
          body: command.body,
          createdAt: acceptedAt,
          messageId: command.messageId,
          senderUid: account.uid,
          sequence,
        },
        participantId: CINDER_PARTICIPANT_ID,
        revision,
        roomId: CINDER_ASSISTANT_ROOM_ID,
        updatedAt: acceptedAt,
      });
      transaction.create(messageReference, {
        accountUid: account.uid,
        aiParticipantId: null,
        aiProvenance: null,
        aiProvider: null,
        assistantId: CINDER_ASSISTANT_ID,
        attachmentIds: [],
        attachments: [],
        authorKind: "HUMAN",
        body: command.body,
        clientCreatedAt: Timestamp.fromMillis(command.clientCreatedAtMillis),
        clientMessageId: command.messageId,
        contentDigest,
        createdAt: acceptedAt,
        deletedAt: null,
        editedAt: null,
        idempotencyKey: command.idempotencyKey,
        replyToMessageId: null,
        revision: 1,
        roomId: CINDER_ASSISTANT_ROOM_ID,
        senderUid: account.uid,
        sequence,
        sourceMessageId: null,
      });
      transaction.create(jobReference, {
        accountUid: account.uid,
        assistantId: CINDER_ASSISTANT_ID,
        attemptCount: 0,
        contentDigest,
        createdAt: acceptedAt,
        directReply: false,
        directReplyToMessageId: null,
        explicitMention: false,
        idempotencyKey: command.idempotencyKey,
        leaseClaimedAt: null,
        leaseDigest: null,
        leaseExpiresAt: null,
        leaseId: null,
        participantActive: true,
        participantId: CINDER_PARTICIPANT_ID,
        participationMode: "AUTO",
        responsePolicy: CINDER_LEGACY_RESPONSE_POLICY,
        roomId: CINDER_ASSISTANT_ROOM_ID,
        roomKind: "ASSISTANT",
        sourceBody: command.body,
        sourceMessageId: command.messageId,
        sourceRevision: 1,
        sourceSenderDisplayName,
        sourceSenderUid: account.uid,
        sourceSequence: sequence,
        sourceServerCreatedAt: acceptedAt,
        state: "PENDING",
        updatedAt: acceptedAt,
      });
    });
    return {
      acceptance,
      messageId: command.messageId,
      revision: 1,
      roomId: CINDER_ASSISTANT_ROOM_ID,
      sequence,
    };
  },
);

export const syncCinderMessages = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{
    hasMore: boolean;
    messages: CinderMessageSyncRecord[];
    nextSequence: number;
  }> => {
    const {uid: accountUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(accountUid, "cinderSyncPolling");
    const command = parseSyncCinderMessagesCommand(request.data);
    const snapshot = await cinderConversationReference(accountUid)
      .collection("messages")
      .orderBy("sequence", "asc")
      .startAfter(command.afterSequence)
      .limit(command.limit + 1)
      .get();
    const hasMore = snapshot.size > command.limit;
    const messages = snapshot.docs
      .slice(0, command.limit)
      .map((messageSnapshot) => readCinderMessageSyncRecord(messageSnapshot, accountUid));
    return {
      hasMore,
      messages,
      nextSequence: messages.at(-1)?.sequence ?? command.afterSequence,
    };
  },
);

export function readCinderAvailabilityReceipt(
  statusSnapshot: DocumentSnapshot,
  checkedAtMillis: number,
  workState: CinderWorkState = "IDLE",
): CinderAvailabilityReceipt {
  const availableUntil = statusSnapshot.get("availableUntil");
  const protocolVersion = statusSnapshot.get("protocolVersion");
  const statusIsValid =
    statusSnapshot.exists &&
    statusSnapshot.id === "current" &&
    statusSnapshot.get("state") === "AVAILABLE" &&
    protocolVersion === CINDER_WORKER_PROTOCOL_VERSION &&
    availableUntil instanceof Timestamp;
  const availableUntilMillis = statusIsValid ? availableUntil.toMillis() : null;
  return {
    available: statusIsValid && isCinderWorkerAvailable(availableUntilMillis, checkedAtMillis),
    availableUntilMillis,
    checkedAtMillis,
    protocolVersion: CINDER_WORKER_PROTOCOL_VERSION,
    workState,
  };
}

export function readCinderMessageSyncRecord(
  snapshot: DocumentSnapshot,
  accountUid: string,
): CinderMessageSyncRecord {
  if (!snapshot.exists) malformedCinderMessage();
  const authorKind = snapshot.get("authorKind");
  const senderUid = snapshot.get("senderUid");
  const aiParticipantId = snapshot.get("aiParticipantId");
  const aiProvenance = snapshot.get("aiProvenance");
  const aiProvider = snapshot.get("aiProvider");
  const assistantId = snapshot.get("assistantId");
  const body = snapshot.get("body");
  const clientCreatedAt = snapshot.get("clientCreatedAt");
  const createdAt = snapshot.get("createdAt");
  const idempotencyKey = snapshot.get("idempotencyKey");
  const revision = snapshot.get("revision");
  const roomId = snapshot.get("roomId");
  const sequence = snapshot.get("sequence");
  const sourceMessageId = snapshot.get("sourceMessageId");
  const humanAttributionIsValid =
    authorKind === "HUMAN" &&
    senderUid === accountUid &&
    aiParticipantId === null &&
    aiProvenance === null &&
    aiProvider === null;
  const remoteAttributionIsValid = isTrustedCinderRemoteAiMessage({
    aiParticipantId,
    aiProvenance,
    aiProvider,
    assistantId,
    authorKind,
    senderUid,
  });
  if (
    snapshot.get("accountUid") !== accountUid ||
    assistantId !== CINDER_ASSISTANT_ID ||
    roomId !== CINDER_ASSISTANT_ROOM_ID ||
    snapshot.get("clientMessageId") !== snapshot.id ||
    (authorKind !== "HUMAN" && authorKind !== "REMOTE_AI") ||
    (!humanAttributionIsValid && !remoteAttributionIsValid) ||
    typeof body !== "string" ||
    body.length === 0 ||
    body.length > 4_000 ||
    !(clientCreatedAt instanceof Timestamp) ||
    !(createdAt instanceof Timestamp) ||
    typeof idempotencyKey !== "string" ||
    idempotencyKey.length === 0 ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 1 ||
    typeof sequence !== "number" ||
    !Number.isSafeInteger(sequence) ||
    sequence < 1 ||
    (sourceMessageId !== null && typeof sourceMessageId !== "string")
  ) {
    malformedCinderMessage();
  }
  return {
    aiParticipantId: aiParticipantId as string | null,
    aiProvenance: aiProvenance as string | null,
    aiProvider: aiProvider as string | null,
    assistantId: CINDER_ASSISTANT_ID,
    authorKind,
    body,
    clientCreatedAtMillis: clientCreatedAt.toMillis(),
    createdAtMillis: createdAt.toMillis(),
    idempotencyKey,
    messageId: snapshot.id,
    revision,
    roomId: CINDER_ASSISTANT_ROOM_ID,
    senderUid,
    sequence,
    sourceMessageId,
  };
}

function readCinderConversation(
  snapshot: DocumentSnapshot,
  accountUid: string,
): {createdAt: Timestamp | null; lastSequence: number; revision: number} {
  if (!snapshot.exists) return {createdAt: null, lastSequence: 0, revision: 0};
  const createdAt = snapshot.get("createdAt");
  const lastSequence = snapshot.get("lastSequence");
  const revision = snapshot.get("revision");
  if (
    snapshot.id !== accountUid ||
    snapshot.get("accountUid") !== accountUid ||
    snapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    snapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    snapshot.get("roomId") !== CINDER_ASSISTANT_ROOM_ID ||
    !(createdAt instanceof Timestamp) ||
    typeof lastSequence !== "number" ||
    !Number.isSafeInteger(lastSequence) ||
    lastSequence < 0 ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 0
  ) {
    throw new HttpsError("data-loss", "Cinder conversation state is malformed.");
  }
  return {createdAt, lastSequence, revision};
}

function requireIdempotentDirectSubmission(
  snapshot: DocumentSnapshot,
  accountUid: string,
  messageId: string,
  idempotencyKey: string,
  contentDigest: string,
): void {
  if (
    snapshot.id !== messageId ||
    snapshot.get("accountUid") !== accountUid ||
    snapshot.get("authorKind") !== "HUMAN" ||
    snapshot.get("senderUid") !== accountUid ||
    snapshot.get("idempotencyKey") !== idempotencyKey ||
    snapshot.get("contentDigest") !== contentDigest ||
    snapshot.get("roomId") !== CINDER_ASSISTANT_ROOM_ID
  ) {
    throw new HttpsError(
      "already-exists",
      "The Cinder message identifier was already used with different content.",
    );
  }
}

function requireMatchingJobOrAudit(
  jobSnapshot: DocumentSnapshot,
  auditSnapshot: DocumentSnapshot,
  contentDigest: string,
  sourceMessageId: string,
): void {
  const snapshot = jobSnapshot.exists ? jobSnapshot : auditSnapshot;
  if (
    !snapshot.exists ||
    snapshot.get("contentDigest") !== contentDigest ||
    snapshot.get("sourceMessageId") !== sourceMessageId
  ) {
    throw new HttpsError(
      "already-exists",
      "The Cinder idempotency key was already used with different content.",
    );
  }
}

function requirePositiveSafeInteger(value: unknown, fieldName: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) {
    throw new HttpsError("data-loss", `Cinder ${fieldName} is malformed.`);
  }
  return value;
}

function malformedCinderMessage(): never {
  throw new HttpsError("data-loss", "Cinder message state is malformed.");
}

function cinderConversationReference(accountUid: string) {
  return firebaseAdminFirestore.doc(`cinderConversations/${accountUid}`);
}

function cinderWorkerStatusReference() {
  return firebaseAdminFirestore.doc("cinderWorkerStatus/current");
}
