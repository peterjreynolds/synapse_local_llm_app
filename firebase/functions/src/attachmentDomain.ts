import {createHash} from "node:crypto";
import {HttpsError} from "firebase-functions/v2/https";

export type RemoteAttachmentKind = "AUDIO" | "DOCUMENT" | "IMAGE" | "VIDEO" | "VOICE_NOTE";

export interface PrepareRemoteAttachmentCommand {
  attachmentId: string;
  byteCount: number;
  displayName: string;
  durationMillis: number | null;
  kind: RemoteAttachmentKind;
  messageId: string;
  mimeType: string;
  roomId: string;
}

export interface FinalizeRemoteAttachmentCommand {
  attachmentId: string;
  messageId: string;
  roomId: string;
}

interface AttachmentPolicy {
  canonicalExtension: string;
  kind: Exclude<RemoteAttachmentKind, "VOICE_NOTE">;
  maximumBytes: number;
}

const attachmentPolicies: Readonly<Record<string, AttachmentPolicy>> = {
  "application/msword": {canonicalExtension: "doc", kind: "DOCUMENT", maximumBytes: 25 * 1024 * 1024},
  "application/pdf": {canonicalExtension: "pdf", kind: "DOCUMENT", maximumBytes: 25 * 1024 * 1024},
  "application/rtf": {canonicalExtension: "rtf", kind: "DOCUMENT", maximumBytes: 25 * 1024 * 1024},
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": {
    canonicalExtension: "docx",
    kind: "DOCUMENT",
    maximumBytes: 25 * 1024 * 1024,
  },
  "audio/mp4": {canonicalExtension: "m4a", kind: "AUDIO", maximumBytes: 25 * 1024 * 1024},
  "audio/mpeg": {canonicalExtension: "mp3", kind: "AUDIO", maximumBytes: 25 * 1024 * 1024},
  "audio/ogg": {canonicalExtension: "ogg", kind: "AUDIO", maximumBytes: 25 * 1024 * 1024},
  "audio/wav": {canonicalExtension: "wav", kind: "AUDIO", maximumBytes: 25 * 1024 * 1024},
  "audio/x-wav": {canonicalExtension: "wav", kind: "AUDIO", maximumBytes: 25 * 1024 * 1024},
  "image/gif": {canonicalExtension: "gif", kind: "IMAGE", maximumBytes: 15 * 1024 * 1024},
  "image/jpeg": {canonicalExtension: "jpg", kind: "IMAGE", maximumBytes: 15 * 1024 * 1024},
  "image/png": {canonicalExtension: "png", kind: "IMAGE", maximumBytes: 15 * 1024 * 1024},
  "image/webp": {canonicalExtension: "webp", kind: "IMAGE", maximumBytes: 15 * 1024 * 1024},
  "video/mp4": {canonicalExtension: "mp4", kind: "VIDEO", maximumBytes: 50 * 1024 * 1024},
  "video/quicktime": {canonicalExtension: "mov", kind: "VIDEO", maximumBytes: 50 * 1024 * 1024},
  "video/webm": {canonicalExtension: "webm", kind: "VIDEO", maximumBytes: 50 * 1024 * 1024},
  "text/csv": {canonicalExtension: "csv", kind: "DOCUMENT", maximumBytes: 10 * 1024 * 1024},
  "text/markdown": {canonicalExtension: "md", kind: "DOCUMENT", maximumBytes: 10 * 1024 * 1024},
  "text/plain": {canonicalExtension: "txt", kind: "DOCUMENT", maximumBytes: 10 * 1024 * 1024},
};

export function parsePrepareRemoteAttachmentCommand(input: unknown): PrepareRemoteAttachmentCommand {
  const command = requireRecord(input);
  const mimeType = typeof command.mimeType === "string" ? command.mimeType.trim().toLowerCase() : "";
  const policy = attachmentPolicies[mimeType];
  if (!policy) invalidAttachmentCommand();
  const requestedKind = command.kind;
  if (
    requestedKind !== "AUDIO" &&
    requestedKind !== "DOCUMENT" &&
    requestedKind !== "IMAGE" &&
    requestedKind !== "VIDEO" &&
    requestedKind !== "VOICE_NOTE"
  ) {
    invalidAttachmentCommand();
  }
  const kind = requestedKind as RemoteAttachmentKind;
  if (
    (kind === "VOICE_NOTE" && mimeType !== "audio/mp4") ||
    (kind !== "VOICE_NOTE" && kind !== policy.kind)
  ) {
    invalidAttachmentCommand();
  }
  const byteCount = command.byteCount;
  if (
    typeof byteCount !== "number" ||
    !Number.isSafeInteger(byteCount) ||
    byteCount < 1 ||
    byteCount > policy.maximumBytes
  ) {
    invalidAttachmentCommand();
  }
  const durationMillis = command.durationMillis;
  if (
    durationMillis !== null &&
    (
      typeof durationMillis !== "number" ||
      !Number.isSafeInteger(durationMillis) ||
      durationMillis < 1 ||
      durationMillis > MAXIMUM_MEDIA_DURATION_MILLIS
    )
  ) {
    invalidAttachmentCommand();
  }
  if (
    ((kind === "AUDIO" || kind === "VOICE_NOTE") && durationMillis === null) ||
    ((kind === "IMAGE" || kind === "DOCUMENT") && durationMillis !== null)
  ) {
    invalidAttachmentCommand();
  }
  return {
    attachmentId: parseAttachmentId(command.attachmentId),
    byteCount,
    displayName: normalizeAttachmentDisplayName(command.displayName, policy.canonicalExtension),
    durationMillis,
    kind,
    messageId: parseMessageId(command.messageId),
    mimeType,
    roomId: parseRemoteRoomId(command.roomId),
  };
}

export function parseFinalizeRemoteAttachmentCommand(input: unknown): FinalizeRemoteAttachmentCommand {
  const command = requireRecord(input);
  return {
    attachmentId: parseAttachmentId(command.attachmentId),
    messageId: parseMessageId(command.messageId),
    roomId: parseRemoteRoomId(command.roomId),
  };
}

export function buildAttachmentCommandDigest(command: PrepareRemoteAttachmentCommand): string {
  return createHash("sha256").update(JSON.stringify(command), "utf8").digest("hex");
}

export function buildAttachmentObjectPath(
  roomId: string,
  messageId: string,
  attachmentId: string,
  variant: "content" | "thumbnail",
): string {
  return `roomAttachments/${roomId}/${messageId}/${attachmentId}/${variant}`;
}

function normalizeAttachmentDisplayName(value: unknown, canonicalExtension: string): string {
  if (typeof value !== "string") invalidAttachmentCommand();
  const leafName = value.normalize("NFKC").split(/[\\/]/u).pop()?.trim() ?? "";
  const extensionIndex = leafName.lastIndexOf(".");
  const rawStem = extensionIndex > 0 ? leafName.slice(0, extensionIndex) : leafName;
  const stem = rawStem
    .replace(/[\u0000-\u001f\u007f]/gu, "")
    .replace(/[^\p{L}\p{N} _.-]+/gu, "_")
    .replace(/\s+/gu, " ")
    .replace(/^[ ._-]+|[ ._-]+$/gu, "")
    .slice(0, MAXIMUM_DISPLAY_NAME_STEM_LENGTH);
  if (stem.length === 0) invalidAttachmentCommand();
  return `${stem}.${canonicalExtension}`;
}

function parseAttachmentId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^attachment-[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(value)
  ) {
    invalidAttachmentCommand();
  }
  return value;
}

function parseMessageId(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) invalidAttachmentCommand();
  return value;
}

function parseRemoteRoomId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^(direct_[a-f0-9]{64}|group_[a-f0-9]{32})$/.test(value)
  ) {
    invalidAttachmentCommand();
  }
  return value;
}

function requireRecord(input: unknown): Record<string, unknown> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) invalidAttachmentCommand();
  return input as Record<string, unknown>;
}

function invalidAttachmentCommand(): never {
  throw new HttpsError("invalid-argument", "Attachment command is invalid.");
}

const MAXIMUM_MEDIA_DURATION_MILLIS = 60 * 60 * 1_000;
const MAXIMUM_DISPLAY_NAME_STEM_LENGTH = 100;
