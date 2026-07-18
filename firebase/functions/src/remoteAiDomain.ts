import {createHash} from "node:crypto";
import {HttpsError} from "firebase-functions/v2/https";
import {DISABLED_HOSTED_AI_POLICY} from "./hostedAiProvider.js";

export const LOCAL_AI_PARTICIPANT_ID = "participant-synapse-local-ai";
export const LOCAL_AI_HOST_AVAILABILITY_MILLIS = 2 * 60 * 1000;
export const LOCAL_AI_LEASE_MILLIS = 2 * 60 * 1000;
export const MAXIMUM_LOCAL_AI_ATTEMPTS = 2;

export type LocalAiResponsePolicy = "AUTOMATIC" | "MENTION_ONLY";
export type LocalAiFailureCode =
  "CANCELLED" | "GENERATION_FAILED" | "MODEL_UNAVAILABLE" | "TIMEOUT";

export interface UpdateRemoteAiConfigurationCommand {
  hostedAiEnabled: boolean;
  localAiAutoResponse: boolean;
  localAiEnabled: boolean;
  localAiHostDeviceId: string | null;
  roomId: string;
}

export interface RemoteRoomAiConfiguration {
  hostedAiEnabled: false;
  hostedAiProviderConfigured: false;
  hostedAiStatus: "DISABLED_NO_PROVIDER";
  hostedExecutionPolicy: typeof DISABLED_HOSTED_AI_POLICY;
  localAiAutoResponse: boolean;
  localAiEnabled: boolean;
  localAiHostAvailable: boolean;
  localAiHostDeviceId: string | null;
  localAiHostLastSeenAtMillis: number | null;
  localAiHostUid: string | null;
  localAiParticipantId: typeof LOCAL_AI_PARTICIPANT_ID;
  roomId: string;
}

export interface CompleteLocalAiResponseCommand {
  body: string;
  deviceId: string;
  jobId: string;
  leaseToken: string;
}

export interface FailLocalAiResponseCommand {
  deviceId: string;
  failureCode: LocalAiFailureCode;
  jobId: string;
  leaseToken: string;
  retryable: boolean;
}

export function parseUpdateRemoteAiConfigurationCommand(
  input: unknown,
): UpdateRemoteAiConfigurationCommand {
  if (
    !isRecord(input) ||
    typeof input.hostedAiEnabled !== "boolean" ||
    typeof input.localAiAutoResponse !== "boolean" ||
    typeof input.localAiEnabled !== "boolean" ||
    (input.localAiHostDeviceId !== null && typeof input.localAiHostDeviceId !== "string")
  ) {
    invalidAiCommand();
  }
  const localAiHostDeviceId = input.localAiHostDeviceId === null ? null : parseDeviceId(input.localAiHostDeviceId);
  if (
    (input.localAiEnabled && localAiHostDeviceId === null) ||
    (!input.localAiEnabled && (input.localAiAutoResponse || localAiHostDeviceId !== null))
  ) {
    invalidAiCommand();
  }
  return {
    hostedAiEnabled: input.hostedAiEnabled,
    localAiAutoResponse: input.localAiAutoResponse,
    localAiEnabled: input.localAiEnabled,
    localAiHostDeviceId,
    roomId: parseRoomId(input.roomId),
  };
}

export function parseRoomAiQuery(input: unknown): {roomId: string} {
  if (!isRecord(input)) invalidAiCommand();
  return {roomId: parseRoomId(input.roomId)};
}

export function parseLocalAiHostCommand(input: unknown): {deviceId: string} {
  if (!isRecord(input)) invalidAiCommand();
  return {deviceId: parseDeviceId(input.deviceId)};
}

export function parseCompleteLocalAiResponseCommand(input: unknown): CompleteLocalAiResponseCommand {
  if (!isRecord(input) || typeof input.body !== "string") invalidAiCommand();
  return {
    body: normalizeAiMessageBody(input.body),
    deviceId: parseDeviceId(input.deviceId),
    jobId: parseJobId(input.jobId),
    leaseToken: parseLeaseToken(input.leaseToken),
  };
}

export function parseFailLocalAiResponseCommand(input: unknown): FailLocalAiResponseCommand {
  if (
    !isRecord(input) ||
    typeof input.retryable !== "boolean" ||
    !isLocalAiFailureCode(input.failureCode)
  ) {
    invalidAiCommand();
  }
  return {
    deviceId: parseDeviceId(input.deviceId),
    failureCode: input.failureCode,
    jobId: parseJobId(input.jobId),
    leaseToken: parseLeaseToken(input.leaseToken),
    retryable: input.retryable,
  };
}

export function parseSkipLocalAiResponseCommand(input: unknown): {
  deviceId: string;
  jobId: string;
  leaseToken: string;
  reason: "MENTION_REQUIRED";
} {
  if (!isRecord(input) || input.reason !== "MENTION_REQUIRED") invalidAiCommand();
  return {
    deviceId: parseDeviceId(input.deviceId),
    jobId: parseJobId(input.jobId),
    leaseToken: parseLeaseToken(input.leaseToken),
    reason: input.reason,
  };
}

export function buildRemoteAiJobId(roomId: string, sourceMessageId: string): string {
  return createHash("sha256").update(`${roomId}\u0000${sourceMessageId}`, "utf8").digest("hex");
}

export function digestLocalAiLeaseToken(leaseToken: string): string {
  return createHash("sha256").update(leaseToken, "utf8").digest("hex");
}

export function readRemoteRoomAiConfiguration(
  roomId: string,
  input: unknown,
  nowMillis: number,
): RemoteRoomAiConfiguration {
  if (input === undefined) return defaultRemoteRoomAiConfiguration(roomId);
  if (!isRecord(input)) malformedAiState();
  const localAiEnabled = input.localAiEnabled;
  const localAiAutoResponse = input.localAiAutoResponse;
  const localAiHostDeviceId = input.localAiHostDeviceId;
  const localAiHostUid = input.localAiHostUid;
  const localAiHostLastSeenAtMillis = readOptionalNonnegativeInteger(input.localAiHostLastSeenAtMillis);
  if (
    typeof localAiEnabled !== "boolean" ||
    typeof localAiAutoResponse !== "boolean" ||
    (localAiHostDeviceId !== null && !isDeviceId(localAiHostDeviceId)) ||
    (localAiHostUid !== null && !isUid(localAiHostUid)) ||
    input.hostedAiEnabled !== false ||
    input.hostedAiProviderConfigured !== false ||
    input.hostedAiStatus !== "DISABLED_NO_PROVIDER" ||
    (localAiEnabled && (localAiHostDeviceId === null || localAiHostUid === null)) ||
    (!localAiEnabled && (localAiAutoResponse || localAiHostDeviceId !== null || localAiHostUid !== null))
  ) {
    malformedAiState();
  }
  return {
    hostedAiEnabled: false,
    hostedAiProviderConfigured: false,
    hostedAiStatus: "DISABLED_NO_PROVIDER",
    hostedExecutionPolicy: DISABLED_HOSTED_AI_POLICY,
    localAiAutoResponse,
    localAiEnabled,
    localAiHostAvailable: localAiEnabled && localAiHostLastSeenAtMillis !== null &&
      nowMillis - localAiHostLastSeenAtMillis <= LOCAL_AI_HOST_AVAILABILITY_MILLIS,
    localAiHostDeviceId,
    localAiHostLastSeenAtMillis,
    localAiHostUid,
    localAiParticipantId: LOCAL_AI_PARTICIPANT_ID,
    roomId,
  };
}

export function defaultRemoteRoomAiConfiguration(roomId: string): RemoteRoomAiConfiguration {
  return {
    hostedAiEnabled: false,
    hostedAiProviderConfigured: false,
    hostedAiStatus: "DISABLED_NO_PROVIDER",
    hostedExecutionPolicy: DISABLED_HOSTED_AI_POLICY,
    localAiAutoResponse: false,
    localAiEnabled: false,
    localAiHostAvailable: false,
    localAiHostDeviceId: null,
    localAiHostLastSeenAtMillis: null,
    localAiHostUid: null,
    localAiParticipantId: LOCAL_AI_PARTICIPANT_ID,
    roomId,
  };
}

export function responsePolicyForConfiguration(localAiAutoResponse: boolean): LocalAiResponsePolicy {
  return localAiAutoResponse ? "AUTOMATIC" : "MENTION_ONLY";
}

function normalizeAiMessageBody(body: string): string {
  const normalized = body.normalize("NFKC").trim();
  if (normalized.length === 0 || normalized.length > 4_000 || /[\u0000\u000b\u000c\u007f]/u.test(normalized)) {
    invalidAiCommand();
  }
  return normalized;
}

function parseRoomId(value: unknown): string {
  if (typeof value !== "string" || !/^(direct_[a-f0-9]{64}|group_[a-f0-9]{32})$/.test(value)) {
    invalidAiCommand();
  }
  return value;
}

function parseDeviceId(value: unknown): string {
  if (!isDeviceId(value)) invalidAiCommand();
  return value;
}

function parseJobId(value: unknown): string {
  if (typeof value !== "string" || !/^[a-f0-9]{64}$/.test(value)) invalidAiCommand();
  return value;
}

function parseLeaseToken(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{32,128}$/.test(value)) invalidAiCommand();
  return value;
}

function isLocalAiFailureCode(value: unknown): value is LocalAiFailureCode {
  return value === "CANCELLED" || value === "GENERATION_FAILED" ||
    value === "MODEL_UNAVAILABLE" || value === "TIMEOUT";
}

function readOptionalNonnegativeInteger(value: unknown): number | null {
  if (value === null) return null;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) malformedAiState();
  return value;
}

function isDeviceId(value: unknown): value is string {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

function isUid(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidAiCommand(): never {
  throw new HttpsError("invalid-argument", "AI participant command is invalid.");
}

function malformedAiState(): never {
  throw new HttpsError("data-loss", "AI participant state is malformed.");
}
