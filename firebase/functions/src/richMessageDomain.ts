import {createHash} from "node:crypto";
import {HttpsError} from "firebase-functions/v2/https";

export const MAXIMUM_MESSAGE_BODY_LENGTH = 4_000;
export const MAXIMUM_ACKNOWLEDGEMENT_MESSAGES = 50;

export interface SendRemoteMessageCommand {
  body: string;
  clientCreatedAtMillis: number;
  messageId: string;
  replyToMessageId: string | null;
  roomId: string;
}

export interface ReviseRemoteMessageCommand {
  body?: string;
  expectedRevision: number;
  messageId: string;
  mutationId: string;
  roomId: string;
}

export interface ToggleRemoteReactionCommand {
  emoji: string;
  messageId: string;
  reacted: boolean;
  roomId: string;
}

export interface AcknowledgeRemoteMessagesCommand {
  messageIds: string[];
  read: boolean;
  roomId: string;
}

export function parseSendRemoteMessageCommand(input: unknown): SendRemoteMessageCommand {
  const command = requireRecord(input);
  const replyToMessageId = command.replyToMessageId;
  if (replyToMessageId !== null && typeof replyToMessageId !== "string") invalidMessageCommand();
  const clientCreatedAtMillis = command.clientCreatedAtMillis;
  if (
    typeof command.body !== "string" ||
    typeof clientCreatedAtMillis !== "number" ||
    !Number.isSafeInteger(clientCreatedAtMillis) ||
    clientCreatedAtMillis < 0
  ) {
    invalidMessageCommand();
  }
  return {
    body: normalizeMessageBody(command.body),
    clientCreatedAtMillis,
    messageId: parseMessageId(command.messageId),
    replyToMessageId: replyToMessageId === null ? null : parseMessageId(replyToMessageId),
    roomId: parseRemoteRoomId(command.roomId),
  };
}

export function parseEditRemoteMessageCommand(input: unknown): ReviseRemoteMessageCommand & {body: string} {
  const command = parseRevisionCommand(input);
  const record = requireRecord(input);
  if (typeof record.body !== "string") invalidMessageCommand();
  return {...command, body: normalizeMessageBody(record.body)};
}

export function parseDeleteRemoteMessageCommand(input: unknown): ReviseRemoteMessageCommand {
  return parseRevisionCommand(input);
}

export function parseToggleRemoteReactionCommand(input: unknown): ToggleRemoteReactionCommand {
  const command = requireRecord(input);
  if (typeof command.reacted !== "boolean" || typeof command.emoji !== "string") {
    invalidMessageCommand();
  }
  const emoji = command.emoji.normalize("NFKC");
  if (
    emoji.length === 0 ||
    emoji.length > 16 ||
    /[\u0000-\u001f\u007f\s]/u.test(emoji)
  ) {
    invalidMessageCommand();
  }
  return {
    emoji,
    messageId: parseMessageId(command.messageId),
    reacted: command.reacted,
    roomId: parseRemoteRoomId(command.roomId),
  };
}

export function parseAcknowledgeRemoteMessagesCommand(input: unknown): AcknowledgeRemoteMessagesCommand {
  const command = requireRecord(input);
  if (!Array.isArray(command.messageIds) || typeof command.read !== "boolean") {
    invalidMessageCommand();
  }
  const messageIds = command.messageIds.map(parseMessageId);
  if (
    messageIds.length === 0 ||
    messageIds.length > MAXIMUM_ACKNOWLEDGEMENT_MESSAGES ||
    new Set(messageIds).size !== messageIds.length
  ) {
    invalidMessageCommand();
  }
  return {
    messageIds,
    read: command.read,
    roomId: parseRemoteRoomId(command.roomId),
  };
}

export function buildMessageMutationReceiptId(
  actorUid: string,
  mutationName: string,
  mutationId: string,
): string {
  return createHash("sha256")
    .update(`${actorUid}:${mutationName}:${mutationId}`, "utf8")
    .digest("hex");
}

export function buildReactionId(actorUid: string, emoji: string): string {
  return createHash("sha256").update(`${actorUid}:${emoji}`, "utf8").digest("hex");
}

export function buildMutationCommandDigest(command: object): string {
  return createHash("sha256").update(JSON.stringify(command), "utf8").digest("hex");
}

function parseRevisionCommand(input: unknown): ReviseRemoteMessageCommand {
  const command = requireRecord(input);
  if (
    typeof command.expectedRevision !== "number" ||
    !Number.isSafeInteger(command.expectedRevision) ||
    command.expectedRevision < 1
  ) {
    invalidMessageCommand();
  }
  return {
    expectedRevision: command.expectedRevision,
    messageId: parseMessageId(command.messageId),
    mutationId: parseMutationId(command.mutationId),
    roomId: parseRemoteRoomId(command.roomId),
  };
}

function normalizeMessageBody(body: string): string {
  const normalized = body.normalize("NFKC").trim();
  if (
    normalized.length === 0 ||
    normalized.length > MAXIMUM_MESSAGE_BODY_LENGTH ||
    /[\u0000\u000b\u000c\u007f]/u.test(normalized)
  ) {
    invalidMessageCommand();
  }
  return normalized;
}

function parseMessageId(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) invalidMessageCommand();
  return value;
}

function parseMutationId(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{16,128}$/.test(value)) invalidMessageCommand();
  return value;
}

function parseRemoteRoomId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^(direct_[a-f0-9]{64}|group_[a-f0-9]{32})$/.test(value)
  ) {
    invalidMessageCommand();
  }
  return value;
}

function requireRecord(input: unknown): Record<string, unknown> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) invalidMessageCommand();
  return input as Record<string, unknown>;
}

function invalidMessageCommand(): never {
  throw new HttpsError("invalid-argument", "Message command is invalid.");
}
