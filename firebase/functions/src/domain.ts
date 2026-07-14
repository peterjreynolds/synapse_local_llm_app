import {createHash} from "node:crypto";

export const MESSAGE_BODY_LIMIT = 4_000;
export const PROFILE_DISPLAY_NAME_LIMIT = 64;
export const PROFILE_BIO_LIMIT = 160;

export interface DirectRoomIdentity {
  directKey: string;
  memberIds: readonly [string, string];
  roomId: string;
}

export interface HumanMessagePayload {
  body: string;
  senderUid: string;
}

export function buildDirectRoomIdentity(firstUid: string, secondUid: string): DirectRoomIdentity {
  if (!firstUid || !secondUid || firstUid === secondUid) {
    throw new Error("A direct room requires two distinct non-empty account identifiers.");
  }
  const memberIds = [firstUid, secondUid].sort() as [string, string];
  const directKey = memberIds.join(":");
  const digest = createHash("sha256").update(directKey, "utf8").digest("hex");
  return {
    directKey,
    memberIds,
    roomId: `direct_${digest}`,
  };
}

export function parseTargetUid(input: unknown): string {
  if (typeof input !== "object" || input === null || !("targetUid" in input)) {
    throw new Error("targetUid is required.");
  }
  const targetUid = (input as {targetUid?: unknown}).targetUid;
  if (typeof targetUid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(targetUid)) {
    throw new Error("targetUid is invalid.");
  }
  return targetUid;
}

export function parseHumanMessagePayload(input: unknown): HumanMessagePayload {
  if (typeof input !== "object" || input === null) {
    throw new Error("Message payload must be an object.");
  }
  const candidate = input as {
    attachmentIds?: unknown;
    authorKind?: unknown;
    body?: unknown;
    senderUid?: unknown;
  };
  if (candidate.authorKind !== "HUMAN") {
    throw new Error("Only human messages trigger remote notifications.");
  }
  if (typeof candidate.senderUid !== "string" || candidate.senderUid.length === 0) {
    throw new Error("Message sender is invalid.");
  }
  const attachmentIds = candidate.attachmentIds ?? [];
  if (!Array.isArray(attachmentIds) || attachmentIds.length > 8 || attachmentIds.some((attachmentId) =>
    typeof attachmentId !== "string" || !/^attachment-[a-f0-9-]{36}$/.test(attachmentId)
  ) || new Set(attachmentIds).size !== attachmentIds.length) {
    throw new Error("Message attachments are invalid.");
  }
  if (typeof candidate.body !== "string" || candidate.body.length > MESSAGE_BODY_LIMIT) {
    throw new Error("Message body is invalid.");
  }
  const body = candidate.body.trim();
  if (body.length === 0 && attachmentIds.length === 0) throw new Error("Message body is invalid.");
  return {
    body: body || (attachmentIds.length === 1 ? "Attachment" : `${attachmentIds.length} attachments`),
    senderUid: candidate.senderUid,
  };
}

export function buildNotificationReceiptId(eventId: string): string {
  if (!eventId) {
    throw new Error("Event identifier is required.");
  }
  return createHash("sha256").update(eventId, "utf8").digest("hex");
}
