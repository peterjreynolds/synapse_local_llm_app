import {createHash} from "node:crypto";
import {HttpsError} from "firebase-functions/v2/https";

export const CINDER_ASSISTANT_ID = "cinder";
export const CINDER_ASSISTANT_ROOM_ID = "assistant_cinder";
export const CINDER_PARTICIPANT_ID = "participant-cinder-remote-ai";
export const CINDER_AI_PROVENANCE = "REMOTE_HOSTED";
export const CINDER_AI_PROVIDER = "OPENCLAW_CINDER";
export const CINDER_LEGACY_RESPONSE_POLICY = "MENTION_ONLY";
export const DEFAULT_CINDER_PARTICIPATION_MODE = "MENTION";
export const CINDER_WORKER_PROTOCOL_VERSION = 1;
export const CINDER_LEASE_MILLIS = 2 * 60 * 1_000;
export const CINDER_WORKER_AVAILABILITY_MILLIS = 2 * 60 * 1_000;
export const MAXIMUM_CINDER_ATTEMPTS = 3;
export const MAXIMUM_CINDER_CONTEXT_MESSAGES = 12;
export const MAXIMUM_CINDER_MESSAGE_LENGTH = 4_000;

export type CinderRoomKind = "ASSISTANT" | "DIRECT" | "GROUP";
export type CinderParticipationMode = "SILENT" | "MENTION" | "AUTO";
export type CinderResponsePolicy = "MENTION_ONLY" | "AUTOMATIC";
export type CinderWorkState = "IDLE" | "QUEUED" | "THINKING";
export type CinderJobState = "PENDING" | "CLAIMED";
export type CinderFailureCode =
  "DISPATCH_FAILED" | "OPENCLAW_UNAVAILABLE" | "TIMEOUT" | "WORKER_SHUTDOWN";
export type CinderWorkerSkipReason = "DISPATCH_SKIPPED" | "NO_VISIBLE_RESPONSE";

export interface SubmitCinderMessageCommand {
  assistantId: typeof CINDER_ASSISTANT_ID;
  attachmentIds: [];
  body: string;
  clientCreatedAtMillis: number;
  idempotencyKey: string;
  messageId: string;
  replyToMessageId: null;
  roomId: typeof CINDER_ASSISTANT_ROOM_ID;
}

export interface SetCinderParticipantCommand {
  active: boolean | null;
  expectedRevision: number | null;
  mode: CinderParticipationMode | null;
  roomId: string;
}

export interface SyncCinderMessagesCommand {
  afterSequence: number;
  limit: number;
}

export interface CinderWorkerClaimCommand {
  supportedResponsePolicies: CinderResponsePolicy[];
  workerId: string;
}

export interface CinderWorkerLeaseCommand {
  jobId: string;
  leaseId: string;
  leaseToken: string;
  workerId: string;
}

export interface CompleteCinderResponseCommand extends CinderWorkerLeaseCommand {
  body: string;
}

export interface FailCinderResponseCommand extends CinderWorkerLeaseCommand {
  failureCode: CinderFailureCode;
  retryable: boolean;
}

export interface SkipCinderResponseCommand extends CinderWorkerLeaseCommand {
  reason: CinderWorkerSkipReason;
}

export interface SendCinderOutboundMessageCommand {
  accountUid: string;
  body: string;
  idempotencyKey: string;
  roomId: string;
  workerId: string;
}

export interface CinderSequenceRecord {
  sequence: number;
}

export function parseSubmitCinderMessageCommand(input: unknown): SubmitCinderMessageCommand {
  const command = requireRecord(input);
  const attachmentIds = command.attachmentIds ?? [];
  const replyToMessageId = command.replyToMessageId ?? null;
  const clientCreatedAtMillis = command.clientCreatedAtMillis;
  if (
    command.assistantId !== CINDER_ASSISTANT_ID ||
    command.roomId !== CINDER_ASSISTANT_ROOM_ID ||
    !Array.isArray(attachmentIds) ||
    attachmentIds.length !== 0 ||
    replyToMessageId !== null ||
    typeof clientCreatedAtMillis !== "number" ||
    !Number.isSafeInteger(clientCreatedAtMillis) ||
    clientCreatedAtMillis < 0 ||
    clientCreatedAtMillis > MAXIMUM_FIRESTORE_TIMESTAMP_MILLIS
  ) {
    invalidCinderCommand();
  }
  return {
    assistantId: CINDER_ASSISTANT_ID,
    attachmentIds: [],
    body: normalizeCinderMessageBody(command.body),
    clientCreatedAtMillis,
    idempotencyKey: parseOpaqueIdentifier(command.idempotencyKey),
    messageId: parseOpaqueIdentifier(command.messageId),
    replyToMessageId: null,
    roomId: CINDER_ASSISTANT_ROOM_ID,
  };
}

export function parseSetCinderParticipantCommand(input: unknown): SetCinderParticipantCommand {
  const command = requireRecord(input);
  const hasMode = Object.prototype.hasOwnProperty.call(command, "mode");
  const hasActive = Object.prototype.hasOwnProperty.call(command, "active");
  if (!hasMode && !hasActive) invalidCinderCommand();
  const mode = hasMode ? parseCinderParticipationMode(command.mode) : null;
  const active = hasActive ? command.active : null;
  if (active !== null && typeof active !== "boolean") invalidCinderCommand();
  const expectedRevision = hasMode ? parseCinderExpectedRevision(command.expectedRevision) : null;
  return {
    active: active as boolean | null,
    expectedRevision,
    mode,
    roomId: parseHumanRoomId(command.roomId),
  };
}

export function parseCinderParticipantQuery(input: unknown): {roomId: string} {
  const command = requireRecord(input);
  return {roomId: parseHumanRoomId(command.roomId)};
}

export function parseSyncCinderMessagesCommand(input: unknown): SyncCinderMessagesCommand {
  const command = requireRecord(input);
  const afterSequence = command.afterSequence ?? 0;
  const limit = command.limit ?? 100;
  if (
    typeof afterSequence !== "number" ||
    !Number.isSafeInteger(afterSequence) ||
    afterSequence < 0 ||
    typeof limit !== "number" ||
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > 100
  ) {
    invalidCinderCommand();
  }
  return {afterSequence, limit};
}

export function parseCinderWorkerClaimCommand(input: unknown): CinderWorkerClaimCommand {
  const command = requireRecord(input);
  const supportedResponsePolicies = command.supportedResponsePolicies ??
    [CINDER_LEGACY_RESPONSE_POLICY];
  if (
    !Array.isArray(supportedResponsePolicies) ||
    supportedResponsePolicies.length < 1 ||
    supportedResponsePolicies.length > 2 ||
    supportedResponsePolicies.some((policy) => policy !== "MENTION_ONLY" && policy !== "AUTOMATIC") ||
    new Set(supportedResponsePolicies).size !== supportedResponsePolicies.length
  ) {
    invalidCinderCommand();
  }
  return {
    supportedResponsePolicies: supportedResponsePolicies as CinderResponsePolicy[],
    workerId: parseWorkerId(command.workerId),
  };
}

export function parseCompleteCinderResponseCommand(input: unknown): CompleteCinderResponseCommand {
  const command = requireRecord(input);
  return {
    ...parseWorkerLeaseCommand(command),
    body: normalizeCinderMessageBody(command.body),
  };
}

export function parseFailCinderResponseCommand(input: unknown): FailCinderResponseCommand {
  const command = requireRecord(input);
  if (!isCinderFailureCode(command.failureCode) || typeof command.retryable !== "boolean") {
    invalidCinderCommand();
  }
  return {
    ...parseWorkerLeaseCommand(command),
    failureCode: command.failureCode,
    retryable: command.retryable,
  };
}

export function parseSkipCinderResponseCommand(input: unknown): SkipCinderResponseCommand {
  const command = requireRecord(input);
  if (command.reason !== "DISPATCH_SKIPPED" && command.reason !== "NO_VISIBLE_RESPONSE") {
    invalidCinderCommand();
  }
  return {
    ...parseWorkerLeaseCommand(command),
    reason: command.reason,
  };
}

export function parseSendCinderOutboundMessageCommand(
  input: unknown,
): SendCinderOutboundMessageCommand {
  const command = requireRecord(input);
  const roomId = command.roomId === CINDER_ASSISTANT_ROOM_ID ?
    CINDER_ASSISTANT_ROOM_ID :
    parseHumanRoomId(command.roomId);
  return {
    accountUid: parseOpaqueIdentifier(command.accountUid),
    body: normalizeCinderMessageBody(command.body),
    idempotencyKey: parseJobId(command.idempotencyKey),
    roomId,
    workerId: parseWorkerId(command.workerId),
  };
}

export function hasExplicitCinderMention(body: string): boolean {
  const normalizedBody = body.normalize("NFKC").toLocaleLowerCase("en-US");
  return /(^|[^\p{L}\p{N}_])@cinder(?=$|[^\p{L}\p{N}_])/u.test(normalizedBody);
}

export function isCinderHumanRoomQueueEligible(input: {
  authorKind: unknown;
  body: unknown;
  participantActive: boolean;
  participantId: unknown;
  participantKind: unknown;
  participantProvenance: unknown;
  participationMode: unknown;
  senderActive: boolean;
}): boolean {
  if (input.authorKind !== "HUMAN" ||
    typeof input.body !== "string" ||
    input.body.length === 0 ||
    !input.participantActive ||
    input.participantId !== CINDER_PARTICIPANT_ID ||
    input.participantKind !== "REMOTE_AI" ||
    input.participantProvenance !== CINDER_AI_PROVENANCE ||
    !input.senderActive
  ) {
    return false;
  }
  return input.participationMode === "AUTO" ||
    (input.participationMode === "MENTION" && hasExplicitCinderMention(input.body));
}

export function cinderResponsePolicyForMode(
  mode: Exclude<CinderParticipationMode, "SILENT">,
): CinderResponsePolicy {
  return mode === "AUTO" ? "AUTOMATIC" : "MENTION_ONLY";
}

export function resolveStoredCinderParticipationMode(input: {
  mode: unknown;
  responsePolicy: unknown;
}): CinderParticipationMode | null {
  if (isCinderParticipationMode(input.mode)) return input.mode;
  return input.mode === undefined && input.responsePolicy === CINDER_LEGACY_RESPONSE_POLICY ?
    DEFAULT_CINDER_PARTICIPATION_MODE : null;
}

export function cinderModeAllowsQueuedResponse(
  mode: CinderParticipationMode,
  explicitMention: boolean,
): boolean {
  return mode === "AUTO" || (mode === "MENTION" && explicitMention);
}

export function cinderModeAllowsProactiveMessage(mode: CinderParticipationMode): boolean {
  return mode === "AUTO";
}

export function resolveCinderWorkState(jobStates: readonly unknown[]): CinderWorkState {
  if (jobStates.some((state) => state === "CLAIMED")) return "THINKING";
  if (jobStates.some((state) => state === "PENDING")) return "QUEUED";
  return "IDLE";
}

export function isCinderParticipationMode(value: unknown): value is CinderParticipationMode {
  return value === "SILENT" || value === "MENTION" || value === "AUTO";
}

/*
 * Participant documents retain responsePolicy=MENTION_ONLY so rolling clients older than the
 * mode contract can continue to read participant state. `mode` is authoritative for all new
 * queue, claim, completion, and outbound decisions.
 */
export function isLegacyCinderParticipantResponsePolicy(value: unknown): boolean {
  return value === CINDER_LEGACY_RESPONSE_POLICY;
}

export function canManageCinderParticipant(
  roomKind: unknown,
  memberRole: unknown,
): boolean {
  return roomKind === "DIRECT" ? memberRole === "MEMBER" :
    roomKind === "GROUP" && (memberRole === "OWNER" || memberRole === "ADMIN");
}

export function buildCinderJobId(input: {
  accountUid: string;
  roomId: string;
  roomKind: CinderRoomKind;
  sourceMessageId: string;
}): string {
  return createHash("sha256")
    .update(
      [input.roomKind, input.accountUid, input.roomId, input.sourceMessageId].join("\u0000"),
      "utf8",
    )
    .digest("hex");
}

export function buildCinderResponseMessageId(jobId: string): string {
  if (!CINDER_JOB_ID_PATTERN.test(jobId)) invalidCinderCommand();
  return `cinder-${jobId}`;
}

export function buildCinderOutboundMessageId(idempotencyKey: string): string {
  if (!CINDER_JOB_ID_PATTERN.test(idempotencyKey)) invalidCinderCommand();
  return `cinder-${idempotencyKey}`;
}

export function buildCinderDirectContentDigest(
  accountUid: string,
  command: SubmitCinderMessageCommand,
): string {
  return createHash("sha256")
    .update(
      [
        accountUid,
        command.assistantId,
        command.roomId,
        command.messageId,
        command.idempotencyKey,
        command.body,
      ].join("\u0000"),
      "utf8",
    )
    .digest("hex");
}

export function buildCinderHumanRoomContentDigest(input: {
  accountUid: string;
  body: string;
  roomId: string;
  sourceMessageId: string;
  sourceRevision: number;
}): string {
  return createHash("sha256")
    .update(
      [
        input.accountUid,
        input.roomId,
        input.sourceMessageId,
        String(input.sourceRevision),
        normalizeCinderMessageBody(input.body),
      ].join("\u0000"),
      "utf8",
    )
    .digest("hex");
}

export function digestCinderLeaseToken(leaseToken: string): string {
  if (!CINDER_LEASE_TOKEN_PATTERN.test(leaseToken)) invalidCinderCommand();
  return createHash("sha256").update(leaseToken, "utf8").digest("hex");
}

export function digestCinderResponseBody(body: string): string {
  return createHash("sha256").update(normalizeCinderMessageBody(body), "utf8").digest("hex");
}

export function digestCinderOutboundMessage(
  command: SendCinderOutboundMessageCommand,
): string {
  return createHash("sha256")
    .update(
      [
        command.workerId,
        command.accountUid,
        command.roomId,
        command.idempotencyKey,
        normalizeCinderMessageBody(command.body),
      ].join("\u0000"),
      "utf8",
    )
    .digest("hex");
}

export function cinderTimestampSequence(seconds: number, nanoseconds: number): number {
  if (
    !Number.isSafeInteger(seconds) ||
    seconds < 0 ||
    !Number.isSafeInteger(nanoseconds) ||
    nanoseconds < 0 ||
    nanoseconds >= 1_000_000_000
  ) {
    throw new Error("Server timestamp is invalid.");
  }
  const sequence = seconds * 1_000_000 + Math.floor(nanoseconds / 1_000);
  if (!Number.isSafeInteger(sequence)) throw new Error("Server timestamp sequence is unsafe.");
  return sequence;
}

export function isCinderWorkerAvailable(
  availableUntilMillis: unknown,
  nowMillis: number,
): boolean {
  return typeof availableUntilMillis === "number" &&
    Number.isSafeInteger(availableUntilMillis) &&
    availableUntilMillis > nowMillis;
}

export function isCinderJobClaimable(input: {
  attemptCount: number;
  leaseExpiresAtMillis: number | null;
  nowMillis: number;
  state: CinderJobState;
}): boolean {
  if (
    !Number.isSafeInteger(input.attemptCount) ||
    input.attemptCount < 0 ||
    input.attemptCount >= MAXIMUM_CINDER_ATTEMPTS
  ) {
    return false;
  }
  if (input.state === "PENDING") return true;
  return input.leaseExpiresAtMillis !== null && input.leaseExpiresAtMillis <= input.nowMillis;
}

export function shouldRetryCinderFailure(
  attemptCount: number,
  retryable: boolean,
): boolean {
  return retryable &&
    Number.isSafeInteger(attemptCount) &&
    attemptCount >= 1 &&
    attemptCount < MAXIMUM_CINDER_ATTEMPTS;
}

export function cinderRecordsAfterCursor<T extends CinderSequenceRecord>(
  records: readonly T[],
  afterSequence: number,
  limit: number,
): T[] {
  if (
    !Number.isSafeInteger(afterSequence) ||
    afterSequence < 0 ||
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > 100
  ) {
    throw new Error("Cinder cursor is invalid.");
  }
  return records
    .filter((record) => Number.isSafeInteger(record.sequence) && record.sequence > afterSequence)
    .sort((first, second) => first.sequence - second.sequence)
    .slice(0, limit);
}

export function isTrustedCinderRemoteAiMessage(input: {
  aiParticipantId?: unknown;
  aiProvenance?: unknown;
  aiProvider?: unknown;
  assistantId?: unknown;
  authorKind?: unknown;
  senderUid?: unknown;
}): boolean {
  return input.authorKind === "REMOTE_AI" &&
    input.senderUid === CINDER_PARTICIPANT_ID &&
    input.aiParticipantId === CINDER_PARTICIPANT_ID &&
    input.aiProvenance === CINDER_AI_PROVENANCE &&
    input.aiProvider === CINDER_AI_PROVIDER &&
    input.assistantId === CINDER_ASSISTANT_ID;
}

export function normalizeCinderMessageBody(value: unknown): string {
  if (typeof value !== "string") invalidCinderCommand();
  const normalized = value.normalize("NFKC").trim();
  if (
    normalized.length === 0 ||
    normalized.length > MAXIMUM_CINDER_MESSAGE_LENGTH ||
    /[\u0000\u000b\u000c\u007f]/u.test(normalized)
  ) {
    invalidCinderCommand();
  }
  return normalized;
}

function parseWorkerLeaseCommand(command: Record<string, unknown>): CinderWorkerLeaseCommand {
  return {
    jobId: parseJobId(command.jobId),
    leaseId: parseLeaseId(command.leaseId),
    leaseToken: parseLeaseToken(command.leaseToken),
    workerId: parseWorkerId(command.workerId),
  };
}

function parseCinderParticipationMode(value: unknown): CinderParticipationMode {
  if (!isCinderParticipationMode(value)) invalidCinderCommand();
  return value;
}

function parseCinderExpectedRevision(value: unknown): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < 0
  ) {
    invalidCinderCommand();
  }
  return value;
}

function parseHumanRoomId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^(direct_[a-f0-9]{64}|group_[a-f0-9]{32})$/.test(value)
  ) {
    invalidCinderCommand();
  }
  return value;
}

function parseOpaqueIdentifier(value: unknown): string {
  if (typeof value !== "string" || !CINDER_OPAQUE_ID_PATTERN.test(value)) {
    invalidCinderCommand();
  }
  return value;
}

function parseJobId(value: unknown): string {
  if (typeof value !== "string" || !CINDER_JOB_ID_PATTERN.test(value)) invalidCinderCommand();
  return value;
}

function parseLeaseId(value: unknown): string {
  if (typeof value !== "string" || !CINDER_LEASE_ID_PATTERN.test(value)) invalidCinderCommand();
  return value;
}

function parseLeaseToken(value: unknown): string {
  if (typeof value !== "string" || !CINDER_LEASE_TOKEN_PATTERN.test(value)) invalidCinderCommand();
  return value;
}

function parseWorkerId(value: unknown): string {
  if (typeof value !== "string" || !CINDER_WORKER_ID_PATTERN.test(value)) invalidCinderCommand();
  return value;
}

function isCinderFailureCode(value: unknown): value is CinderFailureCode {
  return value === "DISPATCH_FAILED" || value === "OPENCLAW_UNAVAILABLE" ||
    value === "TIMEOUT" || value === "WORKER_SHUTDOWN";
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) invalidCinderCommand();
  return value as Record<string, unknown>;
}

function invalidCinderCommand(): never {
  throw new HttpsError("invalid-argument", "Cinder command is invalid.");
}

const CINDER_JOB_ID_PATTERN = /^[a-f0-9]{64}$/;
const CINDER_LEASE_ID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const CINDER_LEASE_TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;
const CINDER_OPAQUE_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const CINDER_WORKER_ID_PATTERN = /^[A-Za-z0-9_.-]{1,64}$/;
const MAXIMUM_FIRESTORE_TIMESTAMP_MILLIS = 253_402_300_799_999;
