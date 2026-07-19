import {createHash} from "node:crypto";
import {
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_PARTICIPANT_ID,
  isTrustedCinderRemoteAiMessage,
} from "./cinderDomain.js";

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

export interface RemoteNotificationMessagePayload extends HumanMessagePayload {
  aiParticipantId: string | null;
  aiProvider: string | null;
  assistantId: string | null;
  authorKind: "HUMAN" | "REMOTE_AI" | "SYNAPSE_AI";
  provenance: "PHONE_LOCAL" | "REMOTE_HOSTED" | null;
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
  const message = parseRemoteNotificationMessagePayload(input);
  if (message.authorKind !== "HUMAN") {
    throw new Error("Only human messages can use the human message boundary.");
  }
  return {body: message.body, senderUid: message.senderUid};
}

export function parseRemoteNotificationMessagePayload(input: unknown): RemoteNotificationMessagePayload {
  if (typeof input !== "object" || input === null) {
    throw new Error("Message payload must be an object.");
  }
  const candidate = input as {
    aiParticipantId?: unknown;
    aiProvenance?: unknown;
    aiProvider?: unknown;
    assistantId?: unknown;
    attachmentIds?: unknown;
    authorKind?: unknown;
    body?: unknown;
    senderUid?: unknown;
  };
  if (
    candidate.authorKind !== "HUMAN" &&
    candidate.authorKind !== "REMOTE_AI" &&
    candidate.authorKind !== "SYNAPSE_AI"
  ) {
    throw new Error("Message author kind is invalid.");
  }
  if (typeof candidate.senderUid !== "string" || candidate.senderUid.length === 0) {
    throw new Error("Message sender is invalid.");
  }
  let aiParticipantId: string | null = null;
  let aiProvider: string | null = null;
  let assistantId: string | null = null;
  let provenance: "PHONE_LOCAL" | "REMOTE_HOSTED" | null = null;
  if (candidate.authorKind === "HUMAN") {
    if (
      candidate.aiParticipantId != null ||
      candidate.aiProvenance != null ||
      candidate.aiProvider != null ||
      candidate.assistantId != null
    ) {
      throw new Error("Human message AI attribution is invalid.");
    }
  } else if (candidate.authorKind === "SYNAPSE_AI") {
    if (
      candidate.senderUid !== "participant-synapse-local-ai" ||
      candidate.aiParticipantId !== "participant-synapse-local-ai" ||
      (candidate.aiProvenance !== "PHONE_LOCAL" && candidate.aiProvenance !== "REMOTE_HOSTED") ||
      candidate.aiProvider != null ||
      candidate.assistantId != null
    ) {
      throw new Error("AI message provenance is invalid.");
    }
    aiParticipantId = candidate.aiParticipantId;
    provenance = candidate.aiProvenance;
  } else if (isTrustedCinderRemoteAiMessage(candidate)) {
    aiParticipantId = CINDER_PARTICIPANT_ID;
    aiProvider = CINDER_AI_PROVIDER;
    assistantId = CINDER_ASSISTANT_ID;
    provenance = CINDER_AI_PROVENANCE;
  } else {
    throw new Error("Remote AI message provenance is invalid.");
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
    aiParticipantId,
    aiProvider,
    assistantId,
    authorKind: candidate.authorKind,
    body: body || (attachmentIds.length === 1 ? "Attachment" : `${attachmentIds.length} attachments`),
    provenance,
    senderUid: candidate.senderUid,
  };
}

export function buildNotificationReceiptId(eventId: string): string {
  if (!eventId) {
    throw new Error("Event identifier is required.");
  }
  return createHash("sha256").update(eventId, "utf8").digest("hex");
}
