import {createHash, randomBytes} from "node:crypto";
import {DocumentReference, DocumentSnapshot, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  readRoomAiConfigurationSnapshot,
  requireRegisteredAiHostDevice,
} from "./remoteAiConfiguration.js";
import {
  digestLocalAiLeaseToken,
  LOCAL_AI_LEASE_MILLIS,
  LOCAL_AI_PARTICIPANT_ID,
  MAXIMUM_LOCAL_AI_ATTEMPTS,
  parseCompleteLocalAiResponseCommand,
  parseFailLocalAiResponseCommand,
  parseLocalAiHostCommand,
  parseSkipLocalAiResponseCommand,
} from "./remoteAiDomain.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

interface RemoteAiClaimMessage {
  authorId: string;
  authorKind: "HUMAN" | "SYNAPSE_AI";
  body: string;
  messageId: string;
}

interface RemoteAiClaim {
  jobId: string;
  leaseExpiresAtMillis: number;
  leaseToken: string;
  recentMessages: RemoteAiClaimMessage[];
  responsePolicy: "AUTOMATIC" | "MENTION_ONLY";
  roomId: string;
  roomKind: "DIRECT" | "GROUP";
  sourceMessage: RemoteAiClaimMessage;
}

interface ClaimedJob {
  attemptCount: number;
  hostUid: string;
  leaseDigest: string;
  leaseExpiresAt: Timestamp;
  responsePolicy: "AUTOMATIC" | "MENTION_ONLY";
  roomId: string;
  sourceMessageId: string;
}

export const claimNextLocalAiResponse = onCall(
  {region: FIREBASE_FUNCTIONS_REGION, timeoutSeconds: 30},
  async (request): Promise<{claim: RemoteAiClaim | null}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const {deviceId} = parseLocalAiHostCommand(request.data);
    const queueReference = firebaseAdminFirestore.collection(`localAiHostQueues/${deviceId}/jobs`);
    const candidateSnapshots = await queueReference.orderBy("createdAt", "asc")
      .limit(MAXIMUM_QUEUE_SCAN_RESULTS)
      .get();
    for (const candidateSnapshot of candidateSnapshots.docs) {
      const leaseToken = randomBytes(32).toString("base64url");
      const claim = await tryClaimJob(candidateSnapshot.ref, actorUid, deviceId, leaseToken);
      if (claim === null) continue;
      const recentMessages = await loadRemoteAiContext(claim.roomId, claim.sourceMessageId);
      const sourceMessage = recentMessages.find((message) => message.messageId === claim.sourceMessageId);
      if (!sourceMessage) {
        await releaseMalformedClaim(candidateSnapshot.ref, claim, "SOURCE_MESSAGE_UNAVAILABLE");
        continue;
      }
      return {
        claim: {
          jobId: candidateSnapshot.id,
          leaseExpiresAtMillis: claim.leaseExpiresAt.toMillis(),
          leaseToken,
          recentMessages,
          responsePolicy: claim.responsePolicy,
          roomId: claim.roomId,
          roomKind: claim.roomKind,
          sourceMessage,
        },
      };
    }
    return {claim: null};
  },
);

export const completeLocalAiResponse = onCall(
  {region: FIREBASE_FUNCTIONS_REGION, timeoutSeconds: 30},
  async (request): Promise<{messageId: string; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseCompleteLocalAiResponseCommand(request.data);
    const jobReference = localAiJobReference(command.deviceId, command.jobId);
    const auditReference = firebaseAdminFirestore.doc(`remoteAiResponseAudits/${command.jobId}`);
    const leaseDigest = digestLocalAiLeaseToken(command.leaseToken);
    const responseBodyDigest = createHash("sha256").update(command.body, "utf8").digest("hex");
    let completedRoomId = "";
    let completedMessageId = "";
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [jobSnapshot, auditSnapshot] = await Promise.all([
        transaction.get(jobReference),
        transaction.get(auditReference),
      ]);
      if (!jobSnapshot.exists) {
        requireIdempotentCompletion(
          auditSnapshot,
          actorUid,
          command.deviceId,
          leaseDigest,
          responseBodyDigest,
        );
        completedRoomId = requireAuditString(auditSnapshot, "roomId");
        completedMessageId = requireAuditString(auditSnapshot, "messageId");
        return;
      }
      const job = requireClaimedJob(jobSnapshot, actorUid, command.deviceId, leaseDigest);
      const roomReference = firebaseAdminFirestore.doc(`rooms/${job.roomId}`);
      const configurationReference = firebaseAdminFirestore.doc(`roomAiConfigurations/${job.roomId}`);
      const participantReference = roomReference.collection("participants").doc(LOCAL_AI_PARTICIPANT_ID);
      const sourceMessageReference = roomReference.collection("messages").doc(job.sourceMessageId);
      const messageId = `synapse-ai-${command.jobId}`;
      const responseMessageReference = roomReference.collection("messages").doc(messageId);
      const authorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
      const [configurationSnapshot, participantSnapshot, sourceMessageSnapshot, responseMessageSnapshot] =
        await Promise.all([
          transaction.get(configurationReference),
          transaction.get(participantReference),
          transaction.get(sourceMessageReference),
          transaction.get(responseMessageReference),
        ]);
      requireCurrentLocalAiHost(job.roomId, configurationSnapshot, participantSnapshot, actorUid, command.deviceId);
      requireActiveSourceMessage(sourceMessageSnapshot);
      if (responseMessageSnapshot.exists) {
        throw new HttpsError("already-exists", "The AI response identifier already exists.");
      }
      const completedAt = Timestamp.now();
      transaction.create(responseMessageReference, {
        aiHostUid: actorUid,
        aiParticipantId: LOCAL_AI_PARTICIPANT_ID,
        aiProvenance: "PHONE_LOCAL",
        aiResponseJobId: command.jobId,
        attachmentIds: [],
        attachments: [],
        authorKind: "SYNAPSE_AI",
        body: command.body,
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
        senderUid: LOCAL_AI_PARTICIPANT_ID,
      });
      transaction.update(roomReference, {
        latestMessage: {
          body: command.body,
          createdAt: completedAt,
          messageId,
          senderUid: LOCAL_AI_PARTICIPANT_ID,
        },
        updatedAt: completedAt,
      });
      transaction.create(auditReference, {
        attemptCount: job.attemptCount,
        completedAt,
        completionState: "COMPLETE",
        hostDeviceId: command.deviceId,
        hostUid: actorUid,
        leaseDigest,
        messageId,
        participantId: LOCAL_AI_PARTICIPANT_ID,
        provenance: "PHONE_LOCAL",
        responseBodyDigest,
        roomId: job.roomId,
        roomKind: authorization.kind,
        sourceMessageId: job.sourceMessageId,
      });
      transaction.delete(jobReference);
      completedRoomId = job.roomId;
      completedMessageId = messageId;
    });
    return {messageId: completedMessageId, roomId: completedRoomId};
  },
);

export const failLocalAiResponse = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{retryScheduled: boolean}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseFailLocalAiResponseCommand(request.data);
    const jobReference = localAiJobReference(command.deviceId, command.jobId);
    const auditReference = firebaseAdminFirestore.doc(`remoteAiResponseAudits/${command.jobId}`);
    const leaseDigest = digestLocalAiLeaseToken(command.leaseToken);
    let retryScheduled = false;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [jobSnapshot, auditSnapshot] = await Promise.all([
        transaction.get(jobReference),
        transaction.get(auditReference),
      ]);
      if (!jobSnapshot.exists) {
        requireIdempotentTerminalAudit(auditSnapshot, actorUid, command.deviceId, leaseDigest, "FAILED");
        return;
      }
      const job = requireClaimedJob(jobSnapshot, actorUid, command.deviceId, leaseDigest);
      const roomReference = firebaseAdminFirestore.doc(`rooms/${job.roomId}`);
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const failedAt = Timestamp.now();
      retryScheduled = command.retryable && job.attemptCount < MAXIMUM_LOCAL_AI_ATTEMPTS;
      if (retryScheduled) {
        transaction.update(jobReference, {
          lastFailureCode: command.failureCode,
          leaseClaimedAt: null,
          leaseDigest: null,
          leaseExpiresAt: null,
          state: "PENDING",
          updatedAt: failedAt,
        });
      } else {
        transaction.create(auditReference, {
          attemptCount: job.attemptCount,
          completedAt: failedAt,
          completionState: "FAILED",
          failureCode: command.failureCode,
          hostDeviceId: command.deviceId,
          hostUid: actorUid,
          leaseDigest,
          participantId: LOCAL_AI_PARTICIPANT_ID,
          provenance: "PHONE_LOCAL",
          roomId: job.roomId,
          sourceMessageId: job.sourceMessageId,
        });
        transaction.delete(jobReference);
      }
    });
    return {retryScheduled};
  },
);

export const skipLocalAiResponse = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{skipped: true}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseSkipLocalAiResponseCommand(request.data);
    const jobReference = localAiJobReference(command.deviceId, command.jobId);
    const auditReference = firebaseAdminFirestore.doc(`remoteAiResponseAudits/${command.jobId}`);
    const leaseDigest = digestLocalAiLeaseToken(command.leaseToken);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [jobSnapshot, auditSnapshot] = await Promise.all([
        transaction.get(jobReference),
        transaction.get(auditReference),
      ]);
      if (!jobSnapshot.exists) {
        requireIdempotentTerminalAudit(auditSnapshot, actorUid, command.deviceId, leaseDigest, "SKIPPED");
        return;
      }
      const job = requireClaimedJob(jobSnapshot, actorUid, command.deviceId, leaseDigest);
      const roomReference = firebaseAdminFirestore.doc(`rooms/${job.roomId}`);
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      transaction.create(auditReference, {
        attemptCount: job.attemptCount,
        completedAt: Timestamp.now(),
        completionState: "SKIPPED",
        hostDeviceId: command.deviceId,
        hostUid: actorUid,
        leaseDigest,
        participantId: LOCAL_AI_PARTICIPANT_ID,
        provenance: "PHONE_LOCAL",
        reason: command.reason,
        roomId: job.roomId,
        sourceMessageId: job.sourceMessageId,
      });
      transaction.delete(jobReference);
    });
    return {skipped: true};
  },
);

async function tryClaimJob(
  jobReference: DocumentReference,
  actorUid: string,
  deviceId: string,
  leaseToken: string,
): Promise<(ClaimedJob & {roomKind: "DIRECT" | "GROUP"}) | null> {
  let claimedJob: (ClaimedJob & {roomKind: "DIRECT" | "GROUP"}) | null = null;
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    await requireRegisteredAiHostDevice(transaction, deviceId, actorUid);
    const jobSnapshot = await transaction.get(jobReference);
    if (!jobSnapshot.exists) return;
    const now = Timestamp.now();
    const state = jobSnapshot.get("state");
    const currentLeaseExpiry = jobSnapshot.get("leaseExpiresAt");
    if (
      state !== "PENDING" &&
      !(state === "CLAIMED" && currentLeaseExpiry instanceof Timestamp && currentLeaseExpiry.toMillis() <= now.toMillis())
    ) {
      return;
    }
    const attemptCount = jobSnapshot.get("attemptCount");
    const roomId = jobSnapshot.get("roomId");
    const sourceMessageId = jobSnapshot.get("sourceMessageId");
    const responsePolicy = jobSnapshot.get("responsePolicy");
    if (
      typeof attemptCount !== "number" ||
      !Number.isSafeInteger(attemptCount) ||
      attemptCount < 0 ||
      typeof roomId !== "string" ||
      typeof sourceMessageId !== "string" ||
      (responsePolicy !== "AUTOMATIC" && responsePolicy !== "MENTION_ONLY") ||
      jobSnapshot.get("hostDeviceId") !== deviceId ||
      jobSnapshot.get("hostUid") !== actorUid
    ) {
      return;
    }
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const configurationReference = firebaseAdminFirestore.doc(`roomAiConfigurations/${roomId}`);
    const participantReference = roomReference.collection("participants").doc(LOCAL_AI_PARTICIPANT_ID);
    const authorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
    if (attemptCount >= MAXIMUM_LOCAL_AI_ATTEMPTS) {
      const auditReference = firebaseAdminFirestore.doc(`remoteAiResponseAudits/${jobSnapshot.id}`);
      const auditSnapshot = await transaction.get(auditReference);
      if (!auditSnapshot.exists) {
        transaction.create(auditReference, {
          attemptCount,
          completedAt: now,
          completionState: "FAILED",
          failureCode: "LEASE_ATTEMPTS_EXHAUSTED",
          hostDeviceId: deviceId,
          hostUid: actorUid,
          leaseDigest: typeof jobSnapshot.get("leaseDigest") === "string" ?
            jobSnapshot.get("leaseDigest") : null,
          participantId: LOCAL_AI_PARTICIPANT_ID,
          provenance: "PHONE_LOCAL",
          roomId,
          roomKind: authorization.kind,
          sourceMessageId,
        });
      }
      transaction.delete(jobReference);
      return;
    }
    const [configurationSnapshot, participantSnapshot] = await Promise.all([
      transaction.get(configurationReference),
      transaction.get(participantReference),
    ]);
    requireCurrentLocalAiHost(roomId, configurationSnapshot, participantSnapshot, actorUid, deviceId);
    const leaseExpiresAt = Timestamp.fromMillis(now.toMillis() + LOCAL_AI_LEASE_MILLIS);
    const leaseDigest = digestLocalAiLeaseToken(leaseToken);
    transaction.update(jobReference, {
      attemptCount: attemptCount + 1,
      leaseClaimedAt: now,
      leaseDigest,
      leaseExpiresAt,
      state: "CLAIMED",
      updatedAt: now,
    });
    transaction.update(configurationReference, {
      localAiHostLastSeenAt: now,
      updatedAt: now,
    });
    claimedJob = {
      attemptCount: attemptCount + 1,
      hostUid: actorUid,
      leaseDigest,
      leaseExpiresAt,
      responsePolicy,
      roomId,
      roomKind: authorization.kind,
      sourceMessageId,
    };
  });
  return claimedJob;
}

async function loadRemoteAiContext(roomId: string, sourceMessageId: string): Promise<RemoteAiClaimMessage[]> {
  const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
  const [recentSnapshot, sourceSnapshot] = await Promise.all([
    roomReference.collection("messages").orderBy("createdAt", "desc").limit(MAXIMUM_CONTEXT_MESSAGES).get(),
    roomReference.collection("messages").doc(sourceMessageId).get(),
  ]);
  const messagesById = new Map<string, RemoteAiClaimMessage>();
  [...recentSnapshot.docs.reverse(), sourceSnapshot].forEach((snapshot) => {
    const message = parseContextMessage(snapshot);
    if (message !== null) messagesById.set(message.messageId, message);
  });
  return [...messagesById.values()].slice(-MAXIMUM_CONTEXT_MESSAGES);
}

function parseContextMessage(snapshot: DocumentSnapshot): RemoteAiClaimMessage | null {
  const authorKind = snapshot.get("authorKind");
  const body = snapshot.get("body");
  const senderUid = snapshot.get("senderUid");
  if (
    !snapshot.exists ||
    snapshot.get("deletedAt") !== null ||
    (authorKind !== "HUMAN" && authorKind !== "SYNAPSE_AI") ||
    typeof body !== "string" ||
    body.length === 0 ||
    body.length > 4_000 ||
    typeof senderUid !== "string"
  ) {
    return null;
  }
  return {authorId: senderUid, authorKind, body, messageId: snapshot.id};
}

function requireClaimedJob(
  snapshot: DocumentSnapshot,
  actorUid: string,
  deviceId: string,
  leaseDigest: string,
): ClaimedJob {
  const attemptCount = snapshot.get("attemptCount");
  const leaseExpiresAt = snapshot.get("leaseExpiresAt");
  const responsePolicy = snapshot.get("responsePolicy");
  const roomId = snapshot.get("roomId");
  const sourceMessageId = snapshot.get("sourceMessageId");
  if (
    snapshot.get("state") !== "CLAIMED" ||
    snapshot.get("hostUid") !== actorUid ||
    snapshot.get("hostDeviceId") !== deviceId ||
    snapshot.get("leaseDigest") !== leaseDigest ||
    !(leaseExpiresAt instanceof Timestamp) ||
    leaseExpiresAt.toMillis() <= Date.now() ||
    typeof attemptCount !== "number" ||
    !Number.isSafeInteger(attemptCount) ||
    attemptCount < 1 ||
    attemptCount > MAXIMUM_LOCAL_AI_ATTEMPTS ||
    (responsePolicy !== "AUTOMATIC" && responsePolicy !== "MENTION_ONLY") ||
    typeof roomId !== "string" ||
    typeof sourceMessageId !== "string"
  ) {
    throw new HttpsError("failed-precondition", "The local AI response lease is unavailable.");
  }
  return {attemptCount, hostUid: actorUid, leaseDigest, leaseExpiresAt, responsePolicy, roomId, sourceMessageId};
}

function requireCurrentLocalAiHost(
  roomId: string,
  configurationSnapshot: DocumentSnapshot,
  participantSnapshot: DocumentSnapshot,
  actorUid: string,
  deviceId: string,
): void {
  const configuration = readRoomAiConfigurationSnapshot(roomId, configurationSnapshot, Date.now());
  if (
    !configuration.localAiEnabled ||
    configuration.localAiHostUid !== actorUid ||
    configuration.localAiHostDeviceId !== deviceId ||
    participantSnapshot.get("active") !== true ||
    participantSnapshot.get("kind") !== "LOCAL_AI" ||
    participantSnapshot.get("participantId") !== LOCAL_AI_PARTICIPANT_ID ||
    participantSnapshot.get("provenance") !== "PHONE_LOCAL"
  ) {
    throw new HttpsError("failed-precondition", "The designated local AI host is unavailable.");
  }
}

function requireActiveSourceMessage(snapshot: DocumentSnapshot): void {
  if (
    !snapshot.exists ||
    snapshot.get("authorKind") !== "HUMAN" ||
    snapshot.get("deletedAt") !== null ||
    typeof snapshot.get("body") !== "string"
  ) {
    throw new HttpsError("failed-precondition", "The source message is unavailable.");
  }
}

async function releaseMalformedClaim(
  jobReference: DocumentReference,
  claim: ClaimedJob,
  failureCode: string,
): Promise<void> {
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const current = await transaction.get(jobReference);
    if (!current.exists || current.get("leaseDigest") !== claim.leaseDigest) return;
    transaction.update(jobReference, {
      lastFailureCode: failureCode,
      leaseClaimedAt: null,
      leaseDigest: null,
      leaseExpiresAt: null,
      state: "PENDING",
      updatedAt: Timestamp.now(),
    });
  });
}

function requireIdempotentCompletion(
  auditSnapshot: DocumentSnapshot,
  actorUid: string,
  deviceId: string,
  leaseDigest: string,
  responseBodyDigest: string,
): void {
  requireIdempotentTerminalAudit(auditSnapshot, actorUid, deviceId, leaseDigest, "COMPLETE");
  if (auditSnapshot.get("responseBodyDigest") !== responseBodyDigest) {
    throw new HttpsError("already-exists", "The AI response job already completed with different content.");
  }
  requireAuditString(auditSnapshot, "messageId");
}

function requireIdempotentTerminalAudit(
  auditSnapshot: DocumentSnapshot,
  actorUid: string,
  deviceId: string,
  leaseDigest: string,
  expectedState: "COMPLETE" | "FAILED" | "SKIPPED",
): void {
  if (
    !auditSnapshot.exists ||
    auditSnapshot.get("completionState") !== expectedState ||
    auditSnapshot.get("hostUid") !== actorUid ||
    auditSnapshot.get("hostDeviceId") !== deviceId ||
    auditSnapshot.get("leaseDigest") !== leaseDigest
  ) {
    throw new HttpsError("failed-precondition", "The local AI response lease is unavailable.");
  }
}

function requireAuditString(snapshot: DocumentSnapshot, fieldName: string): string {
  const value = snapshot.get(fieldName);
  if (typeof value !== "string" || value.length === 0) {
    throw new HttpsError("data-loss", "The local AI response receipt is malformed.");
  }
  return value;
}

function localAiJobReference(deviceId: string, jobId: string): DocumentReference {
  return firebaseAdminFirestore.doc(`localAiHostQueues/${deviceId}/jobs/${jobId}`);
}

const MAXIMUM_CONTEXT_MESSAGES = 8;
const MAXIMUM_QUEUE_SCAN_RESULTS = 5;
