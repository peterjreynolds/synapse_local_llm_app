import {randomBytes, randomUUID} from "node:crypto";
import {DocumentSnapshot, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {
  CinderFailureCode,
  CinderWorkerSkipReason,
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_LEASE_MILLIS,
  CINDER_PARTICIPANT_ID,
  CINDER_RESPONSE_POLICY,
  CINDER_WORKER_AVAILABILITY_MILLIS,
  CINDER_WORKER_PROTOCOL_VERSION,
  digestCinderLeaseToken,
  FailCinderResponseCommand,
  MAXIMUM_CINDER_ATTEMPTS,
  shouldRetryCinderFailure,
  SkipCinderResponseCommand,
} from "./cinderDomain.js";
import {CinderClaimContextMessage, loadCinderJobContext} from "./cinderContext.js";
import {
  CinderJobDocument,
  cinderAuditReference,
  cinderConversationReference,
  cinderJobCanBeClaimed,
  cinderJobReference,
  readCinderJob,
  requireClaimedCinderJob,
  requireIdempotentCinderTerminalAudit,
  terminalCinderAuditFields,
} from "./cinderJob.js";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CinderResponseClaim {
  accountUid: string;
  assistantId: typeof CINDER_ASSISTANT_ID;
  explicitMention: boolean;
  idempotencyKey: string;
  jobId: string;
  leaseExpiresAtMillis: number;
  leaseId: string;
  leaseToken: string;
  participantActive: true;
  participantId: typeof CINDER_PARTICIPANT_ID;
  provenance: typeof CINDER_AI_PROVENANCE;
  provider: typeof CINDER_AI_PROVIDER;
  recentMessages: CinderClaimContextMessage[];
  responsePolicy: typeof CINDER_RESPONSE_POLICY;
  roomId: string;
  roomKind: "ASSISTANT" | "DIRECT" | "GROUP";
  sourceMessage: CinderClaimContextMessage;
}

export interface CinderFailureReceipt {
  completionState: "FAILED" | "RETRY_SCHEDULED";
  retryScheduled: boolean;
}

export async function claimNextCinderResponse(
  workerId: string,
): Promise<{claim: CinderResponseClaim | null; protocolVersion: number}> {
  const heartbeatAt = Timestamp.now();
  await recordCinderWorkerHeartbeat(heartbeatAt);
  const candidates = await loadCinderClaimCandidates(heartbeatAt);
  for (const candidate of candidates) {
    const leaseId = randomUUID();
    const leaseToken = randomBytes(32).toString("base64url");
    const claimedJob = await tryClaimCinderJob(candidate, workerId, leaseId, leaseToken);
    if (claimedJob === null) continue;
    const context = await loadCinderJobContext(claimedJob);
    return {
      claim: {
        accountUid: claimedJob.accountUid,
        assistantId: CINDER_ASSISTANT_ID,
        explicitMention: claimedJob.explicitMention,
        idempotencyKey: claimedJob.idempotencyKey,
        jobId: candidate.id,
        leaseExpiresAtMillis: claimedJob.leaseExpiresAt.toMillis(),
        leaseId,
        leaseToken,
        participantActive: true,
        participantId: CINDER_PARTICIPANT_ID,
        provenance: CINDER_AI_PROVENANCE,
        provider: CINDER_AI_PROVIDER,
        recentMessages: context.recentMessages,
        responsePolicy: CINDER_RESPONSE_POLICY,
        roomId: claimedJob.roomId,
        roomKind: claimedJob.roomKind,
        sourceMessage: context.sourceMessage,
      },
      protocolVersion: CINDER_WORKER_PROTOCOL_VERSION,
    };
  }
  return {claim: null, protocolVersion: CINDER_WORKER_PROTOCOL_VERSION};
}

export async function failCinderResponse(
  command: FailCinderResponseCommand,
): Promise<CinderFailureReceipt> {
  const jobReference = cinderJobReference(command.jobId);
  const auditReference = cinderAuditReference(command.jobId);
  let receipt: CinderFailureReceipt = {completionState: "FAILED", retryScheduled: false};
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const [jobSnapshot, auditSnapshot] = await Promise.all([
      transaction.get(jobReference),
      transaction.get(auditReference),
    ]);
    if (!jobSnapshot.exists) {
      requireIdempotentCinderTerminalAudit(auditSnapshot, command, "FAILED");
      if (
        auditSnapshot.get("failureCode") !== command.failureCode ||
        auditSnapshot.get("retryable") !== command.retryable
      ) {
        throw new HttpsError(
          "already-exists",
          "The Cinder response job already failed with a different outcome.",
        );
      }
      return;
    }
    if (isIdempotentScheduledFailure(jobSnapshot, command)) {
      receipt = {completionState: "RETRY_SCHEDULED", retryScheduled: true};
      return;
    }
    const failedAt = Timestamp.now();
    const job = requireClaimedCinderJob(jobSnapshot, command, failedAt.toMillis());
    const retryScheduled = shouldRetryCinderFailure(job.attemptCount, command.retryable);
    if (retryScheduled) {
      transaction.update(jobReference, {
        lastFailureCode: command.failureCode,
        lastFailureLeaseDigest: job.leaseDigest,
        lastFailureLeaseId: job.leaseId,
        lastFailureWorkerId: job.workerId,
        leaseClaimedAt: null,
        leaseDigest: null,
        leaseExpiresAt: null,
        leaseId: null,
        state: "PENDING",
        updatedAt: failedAt,
        workerId: null,
      });
      receipt = {completionState: "RETRY_SCHEDULED", retryScheduled: true};
      return;
    }
    transaction.create(auditReference, {
      ...terminalCinderAuditFields(command.jobId, job, "FAILED", failedAt),
      failureCode: command.failureCode,
      retryable: command.retryable,
    });
    transaction.delete(jobReference);
  });
  return receipt;
}

export async function skipCinderResponse(
  command: SkipCinderResponseCommand,
): Promise<{completionState: "SKIPPED"; reason: CinderWorkerSkipReason}> {
  const jobReference = cinderJobReference(command.jobId);
  const auditReference = cinderAuditReference(command.jobId);
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const [jobSnapshot, auditSnapshot] = await Promise.all([
      transaction.get(jobReference),
      transaction.get(auditReference),
    ]);
    if (!jobSnapshot.exists) {
      requireIdempotentCinderTerminalAudit(auditSnapshot, command, "SKIPPED");
      if (auditSnapshot.get("reason") !== command.reason) {
        throw new HttpsError("already-exists", "The Cinder response job was skipped for another reason.");
      }
      return;
    }
    const skippedAt = Timestamp.now();
    const job = requireClaimedCinderJob(jobSnapshot, command, skippedAt.toMillis());
    transaction.create(auditReference, {
      ...terminalCinderAuditFields(command.jobId, job, "SKIPPED", skippedAt),
      reason: command.reason,
    });
    transaction.delete(jobReference);
  });
  return {completionState: "SKIPPED", reason: command.reason};
}

async function recordCinderWorkerHeartbeat(heartbeatAt: Timestamp): Promise<void> {
  await firebaseAdminFirestore.doc("cinderWorkerStatus/current").set({
    availableUntil: Timestamp.fromMillis(
      heartbeatAt.toMillis() + CINDER_WORKER_AVAILABILITY_MILLIS,
    ),
    lastSeenAt: heartbeatAt,
    protocolVersion: CINDER_WORKER_PROTOCOL_VERSION,
    state: "AVAILABLE",
  });
}

async function loadCinderClaimCandidates(now: Timestamp): Promise<DocumentSnapshot[]> {
  const jobs = firebaseAdminFirestore.collection("cinderResponseJobs");
  const [pendingSnapshot, expiredSnapshot] = await Promise.all([
    jobs.where("state", "==", "PENDING")
      .orderBy("createdAt", "asc")
      .limit(MAXIMUM_CLAIM_CANDIDATES)
      .get(),
    jobs.where("state", "==", "CLAIMED")
      .where("leaseExpiresAt", "<=", now)
      .orderBy("leaseExpiresAt", "asc")
      .limit(MAXIMUM_CLAIM_CANDIDATES)
      .get(),
  ]);
  const candidatesById = new Map<string, DocumentSnapshot>();
  [...pendingSnapshot.docs, ...expiredSnapshot.docs].forEach((snapshot) => {
    candidatesById.set(snapshot.id, snapshot);
  });
  return [...candidatesById.values()].sort((first, second) => {
    const firstCreatedAt = first.get("createdAt");
    const secondCreatedAt = second.get("createdAt");
    const timeComparison =
      (firstCreatedAt instanceof Timestamp ? firstCreatedAt.toMillis() : Number.MAX_SAFE_INTEGER) -
      (secondCreatedAt instanceof Timestamp ? secondCreatedAt.toMillis() : Number.MAX_SAFE_INTEGER);
    return timeComparison || first.id.localeCompare(second.id);
  });
}

async function tryClaimCinderJob(
  candidate: DocumentSnapshot,
  workerId: string,
  leaseId: string,
  leaseToken: string,
): Promise<ReturnType<typeof requireClaimedCinderJob> | null> {
  let claimedJob: ReturnType<typeof requireClaimedCinderJob> | null = null;
  const jobReference = cinderJobReference(candidate.id);
  const auditReference = cinderAuditReference(candidate.id);
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const [jobSnapshot, auditSnapshot] = await Promise.all([
      transaction.get(jobReference),
      transaction.get(auditReference),
    ]);
    if (!jobSnapshot.exists) return;
    if (auditSnapshot.exists) {
      transaction.delete(jobReference);
      return;
    }
    let job: CinderJobDocument;
    try {
      job = readCinderJob(jobSnapshot);
    } catch {
      transaction.create(auditReference, {
        completedAt: Timestamp.now(),
        completionState: "FAILED",
        failureCode: "MALFORMED_JOB",
        jobId: candidate.id,
      });
      transaction.delete(jobReference);
      return;
    }
    const claimedAt = Timestamp.now();
    if (job.attemptCount >= MAXIMUM_CINDER_ATTEMPTS) {
      if (
        job.state === "PENDING" ||
        (job.leaseExpiresAt !== null && job.leaseExpiresAt.toMillis() <= claimedAt.toMillis())
      ) {
        transaction.create(auditReference, {
          ...terminalCinderAuditFields(candidate.id, job, "FAILED", claimedAt),
          failureCode: "LEASE_ATTEMPTS_EXHAUSTED",
        });
        transaction.delete(jobReference);
      }
      return;
    }
    if (!cinderJobCanBeClaimed(job, claimedAt.toMillis())) return;
    const sourceState = await readCinderJobSourceState(transaction, job);
    if (sourceState !== "ACTIVE") {
      transaction.create(auditReference, {
        ...terminalCinderAuditFields(candidate.id, job, "SKIPPED", claimedAt),
        reason: sourceState,
      });
      transaction.delete(jobReference);
      return;
    }
    const leaseDigest = digestCinderLeaseToken(leaseToken);
    const leaseExpiresAt = Timestamp.fromMillis(claimedAt.toMillis() + CINDER_LEASE_MILLIS);
    transaction.update(jobReference, {
      attemptCount: job.attemptCount + 1,
      leaseClaimedAt: claimedAt,
      leaseDigest,
      leaseExpiresAt,
      leaseId,
      state: "CLAIMED",
      updatedAt: claimedAt,
      workerId,
    });
    claimedJob = {
      ...job,
      attemptCount: job.attemptCount + 1,
      leaseDigest,
      leaseExpiresAt,
      leaseId,
      state: "CLAIMED",
      workerId,
    };
  });
  return claimedJob;
}

async function readCinderJobSourceState(
  transaction: Transaction,
  job: CinderJobDocument,
): Promise<"ACTIVE" | "PARTICIPANT_REMOVED" | "SOURCE_UNAVAILABLE"> {
  if (job.roomKind === "ASSISTANT") {
    const sourceSnapshot = await transaction.get(
      cinderConversationReference(job.accountUid).collection("messages").doc(job.sourceMessageId),
    );
    return sourceSnapshot.exists &&
      sourceSnapshot.get("authorKind") === "HUMAN" &&
      sourceSnapshot.get("senderUid") === job.accountUid &&
      sourceSnapshot.get("contentDigest") === job.contentDigest &&
      sourceSnapshot.get("deletedAt") === null ? "ACTIVE" : "SOURCE_UNAVAILABLE";
  }
  const roomReference = firebaseAdminFirestore.doc(`rooms/${job.roomId}`);
  const [roomSnapshot, participantSnapshot, sourceSnapshot] = await Promise.all([
    transaction.get(roomReference),
    transaction.get(roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID)),
    transaction.get(roomReference.collection("messages").doc(job.sourceMessageId)),
  ]);
  if (
    !roomSnapshot.exists ||
    roomSnapshot.get("deletedAt") !== null ||
    roomSnapshot.get("kind") !== job.roomKind
  ) {
    return "SOURCE_UNAVAILABLE";
  }
  if (
    !participantSnapshot.exists ||
    participantSnapshot.get("active") !== true ||
    participantSnapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    participantSnapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    participantSnapshot.get("provenance") !== CINDER_AI_PROVENANCE ||
    participantSnapshot.get("provider") !== CINDER_AI_PROVIDER ||
    participantSnapshot.get("responsePolicy") !== CINDER_RESPONSE_POLICY
  ) {
    return "PARTICIPANT_REMOVED";
  }
  return sourceSnapshot.exists &&
    sourceSnapshot.get("authorKind") === "HUMAN" &&
    sourceSnapshot.get("senderUid") === job.sourceSenderUid &&
    sourceSnapshot.get("deletedAt") === null ? "ACTIVE" : "SOURCE_UNAVAILABLE";
}

function isIdempotentScheduledFailure(
  jobSnapshot: DocumentSnapshot,
  command: FailCinderResponseCommand,
): boolean {
  return jobSnapshot.get("state") === "PENDING" &&
    command.retryable === true &&
    jobSnapshot.get("lastFailureLeaseId") === command.leaseId &&
    jobSnapshot.get("lastFailureLeaseDigest") === digestCinderLeaseToken(command.leaseToken) &&
    jobSnapshot.get("lastFailureWorkerId") === command.workerId &&
    jobSnapshot.get("lastFailureCode") === command.failureCode;
}

const MAXIMUM_CLAIM_CANDIDATES = 10;
