import {DocumentReference, DocumentSnapshot, Timestamp} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {
  buildCinderJobId,
  cinderResponsePolicyForMode,
  CinderParticipationMode,
  CinderResponsePolicy,
  CinderJobState,
  CinderRoomKind,
  CINDER_ASSISTANT_ID,
  CINDER_LEGACY_RESPONSE_POLICY,
  CINDER_PARTICIPANT_ID,
  digestCinderLeaseToken,
  isCinderParticipationMode,
  isCinderJobClaimable,
  MAXIMUM_CINDER_ATTEMPTS,
} from "./cinderDomain.js";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CinderJobDocument {
  accountUid: string;
  attemptCount: number;
  contentDigest: string;
  createdAt: Timestamp;
  explicitMention: boolean;
  idempotencyKey: string;
  leaseDigest: string | null;
  leaseExpiresAt: Timestamp | null;
  leaseId: string | null;
  participantActive: true;
  participationMode: Exclude<CinderParticipationMode, "SILENT">;
  responsePolicy: CinderResponsePolicy;
  roomId: string;
  roomKind: CinderRoomKind;
  sourceBody: string;
  sourceMessageId: string;
  sourceRevision: number;
  sourceSenderDisplayName: string;
  sourceSenderUid: string;
  sourceSequence: number;
  sourceServerCreatedAt: Timestamp;
  state: CinderJobState;
  workerId: string | null;
}

export interface ClaimedCinderJob extends CinderJobDocument {
  leaseDigest: string;
  leaseExpiresAt: Timestamp;
  leaseId: string;
  state: "CLAIMED";
  workerId: string;
}

export function readCinderJob(snapshot: DocumentSnapshot): CinderJobDocument {
  if (!snapshot.exists) malformedCinderJob();
  const accountUid = snapshot.get("accountUid");
  const attemptCount = snapshot.get("attemptCount");
  const contentDigest = snapshot.get("contentDigest");
  const createdAt = snapshot.get("createdAt");
  const explicitMention = snapshot.get("explicitMention");
  const idempotencyKey = snapshot.get("idempotencyKey");
  const leaseDigest = snapshot.get("leaseDigest");
  const leaseExpiresAt = snapshot.get("leaseExpiresAt");
  const leaseId = snapshot.get("leaseId");
  const participantActive = snapshot.get("participantActive");
  const persistedParticipationMode = snapshot.get("participationMode");
  const responsePolicy = snapshot.get("responsePolicy");
  const roomId = snapshot.get("roomId");
  const roomKind = snapshot.get("roomKind");
  const sourceBody = snapshot.get("sourceBody");
  const sourceMessageId = snapshot.get("sourceMessageId");
  const sourceRevision = snapshot.get("sourceRevision");
  const sourceSenderDisplayName = snapshot.get("sourceSenderDisplayName");
  const sourceSenderUid = snapshot.get("sourceSenderUid");
  const sourceSequence = snapshot.get("sourceSequence");
  const sourceServerCreatedAt = snapshot.get("sourceServerCreatedAt");
  const state = snapshot.get("state");
  const workerId = snapshot.get("workerId") ?? null;
  const participationMode = persistedParticipationMode === undefined &&
      responsePolicy === CINDER_LEGACY_RESPONSE_POLICY ?
    roomKind === "ASSISTANT" ? "AUTO" : "MENTION" :
    isCinderParticipationMode(persistedParticipationMode) && persistedParticipationMode !== "SILENT" ?
      persistedParticipationMode : null;
  const expectedResponsePolicy = participationMode === null ? null :
    roomKind === "ASSISTANT" ? CINDER_LEGACY_RESPONSE_POLICY :
      cinderResponsePolicyForMode(participationMode);
  const legacyAssistantPolicy = persistedParticipationMode === undefined &&
    roomKind === "ASSISTANT" &&
    responsePolicy === CINDER_LEGACY_RESPONSE_POLICY;
  const leaseStateIsValid = state === "PENDING" ?
    leaseDigest === null && leaseExpiresAt === null && leaseId === null && workerId === null :
    state === "CLAIMED" &&
      typeof leaseDigest === "string" &&
      /^[a-f0-9]{64}$/.test(leaseDigest) &&
      leaseExpiresAt instanceof Timestamp &&
      typeof leaseId === "string" &&
      typeof workerId === "string";
  if (
    snapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
    snapshot.get("participantId") !== CINDER_PARTICIPANT_ID ||
    participationMode === null ||
    (responsePolicy !== expectedResponsePolicy && !legacyAssistantPolicy) ||
    typeof accountUid !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(accountUid) ||
    typeof attemptCount !== "number" ||
    !Number.isSafeInteger(attemptCount) ||
    attemptCount < 0 ||
    attemptCount > MAXIMUM_CINDER_ATTEMPTS ||
    typeof contentDigest !== "string" ||
    !/^[a-f0-9]{64}$/.test(contentDigest) ||
    !(createdAt instanceof Timestamp) ||
    typeof explicitMention !== "boolean" ||
    (roomKind === "ASSISTANT" && explicitMention !== false) ||
    (roomKind !== "ASSISTANT" && participationMode === "MENTION" && explicitMention !== true) ||
    typeof idempotencyKey !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(idempotencyKey) ||
    participantActive !== true ||
    typeof roomId !== "string" ||
    (roomKind !== "ASSISTANT" && roomKind !== "DIRECT" && roomKind !== "GROUP") ||
    !validRoomIdentity(roomId, roomKind) ||
    typeof sourceBody !== "string" ||
    sourceBody.length === 0 ||
    sourceBody.length > 4_000 ||
    typeof sourceMessageId !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(sourceMessageId) ||
    typeof sourceRevision !== "number" ||
    !Number.isSafeInteger(sourceRevision) ||
    sourceRevision < 1 ||
    typeof sourceSenderDisplayName !== "string" ||
    sourceSenderDisplayName.length === 0 ||
    sourceSenderDisplayName.length > 64 ||
    typeof sourceSenderUid !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(sourceSenderUid) ||
    typeof sourceSequence !== "number" ||
    !Number.isSafeInteger(sourceSequence) ||
    sourceSequence < 1 ||
    !(sourceServerCreatedAt instanceof Timestamp) ||
    !leaseStateIsValid
  ) {
    malformedCinderJob();
  }
  const derivedJobId = buildCinderJobId({
    accountUid,
    roomId,
    roomKind,
    sourceMessageId: roomKind === "ASSISTANT" ? idempotencyKey : sourceMessageId,
  });
  if (snapshot.id !== derivedJobId) malformedCinderJob();
  return {
    accountUid,
    attemptCount,
    contentDigest,
    createdAt,
    explicitMention,
    idempotencyKey,
    leaseDigest: leaseDigest as string | null,
    leaseExpiresAt: leaseExpiresAt as Timestamp | null,
    leaseId: leaseId as string | null,
    participantActive: true,
    participationMode,
    responsePolicy: responsePolicy as CinderResponsePolicy,
    roomId,
    roomKind,
    sourceBody,
    sourceMessageId,
    sourceRevision,
    sourceSenderDisplayName,
    sourceSenderUid,
    sourceSequence,
    sourceServerCreatedAt,
    state,
    workerId: workerId as string | null,
  };
}

export function requireClaimedCinderJob(
  snapshot: DocumentSnapshot,
  command: {leaseId: string; leaseToken: string; workerId: string},
  nowMillis: number,
): ClaimedCinderJob {
  const job = readCinderJob(snapshot);
  const leaseDigest = digestCinderLeaseToken(command.leaseToken);
  if (
    job.state !== "CLAIMED" ||
    job.workerId !== command.workerId ||
    job.leaseId !== command.leaseId ||
    job.leaseDigest !== leaseDigest ||
    job.leaseExpiresAt === null ||
    job.leaseExpiresAt.toMillis() <= nowMillis
  ) {
    throw new HttpsError("failed-precondition", "The Cinder response lease is unavailable.");
  }
  return {
    ...job,
    leaseDigest,
    leaseExpiresAt: job.leaseExpiresAt,
    leaseId: command.leaseId,
    state: "CLAIMED",
    workerId: command.workerId,
  };
}

export function cinderJobCanBeClaimed(job: CinderJobDocument, nowMillis: number): boolean {
  return isCinderJobClaimable({
    attemptCount: job.attemptCount,
    leaseExpiresAtMillis: job.leaseExpiresAt?.toMillis() ?? null,
    nowMillis,
    state: job.state,
  });
}

export function cinderJobReference(jobId: string): DocumentReference {
  requireJobId(jobId);
  return firebaseAdminFirestore.doc(`cinderResponseJobs/${jobId}`);
}

export function cinderAuditReference(jobId: string): DocumentReference {
  requireJobId(jobId);
  return firebaseAdminFirestore.doc(`cinderResponseAudits/${jobId}`);
}

export function cinderConversationReference(accountUid: string): DocumentReference {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(accountUid)) malformedCinderJob();
  return firebaseAdminFirestore.doc(`cinderConversations/${accountUid}`);
}

export function requireIdempotentCinderTerminalAudit(
  auditSnapshot: DocumentSnapshot,
  command: {leaseId: string; leaseToken: string; workerId: string},
  expectedState: "COMPLETE" | "FAILED" | "SKIPPED",
): void {
  if (
    !auditSnapshot.exists ||
    auditSnapshot.get("completionState") !== expectedState ||
    auditSnapshot.get("workerId") !== command.workerId ||
    auditSnapshot.get("leaseId") !== command.leaseId ||
    auditSnapshot.get("leaseDigest") !== digestCinderLeaseToken(command.leaseToken)
  ) {
    throw new HttpsError("failed-precondition", "The Cinder response lease is unavailable.");
  }
}

export function terminalCinderAuditFields(
  jobId: string,
  job: CinderJobDocument,
  completionState: "COMPLETE" | "FAILED" | "SKIPPED",
  completedAt: Timestamp,
): Record<string, unknown> {
  return {
    accountUid: job.accountUid,
    assistantId: CINDER_ASSISTANT_ID,
    attemptCount: job.attemptCount,
    completedAt,
    completionState,
    contentDigest: job.contentDigest,
    explicitMention: job.explicitMention,
    idempotencyKey: job.idempotencyKey,
    jobId,
    leaseDigest: job.leaseDigest,
    leaseId: job.leaseId,
    participantId: CINDER_PARTICIPANT_ID,
    participationMode: job.participationMode,
    responsePolicy: job.responsePolicy,
    roomId: job.roomId,
    roomKind: job.roomKind,
    sourceMessageId: job.sourceMessageId,
    sourceRevision: job.sourceRevision,
    sourceSequence: job.sourceSequence,
    sourceServerCreatedAt: job.sourceServerCreatedAt,
    workerId: job.workerId,
  };
}

function validRoomIdentity(roomId: string, roomKind: CinderRoomKind): boolean {
  return roomKind === "ASSISTANT" ? roomId === "assistant_cinder" :
    roomKind === "DIRECT" ? /^direct_[a-f0-9]{64}$/.test(roomId) :
      /^group_[a-f0-9]{32}$/.test(roomId);
}

function requireJobId(jobId: string): void {
  if (!/^[a-f0-9]{64}$/.test(jobId)) malformedCinderJob();
}

function malformedCinderJob(): never {
  throw new HttpsError("data-loss", "Cinder response job state is malformed.");
}
