const DIRECT_CALL_ID_PATTERN = /^call_[a-f0-9]{32}$/;
const DIRECT_CALL_SIGNAL_ID_PATTERN = /^signal_[a-f0-9]{32}$/;
const DIRECT_ROOM_ID_PATTERN = /^direct_[a-f0-9]{64}$/;

export type DirectCallSignalKind = "ANSWER" | "ICE" | "OFFER";

export type DirectCallSignalCommand =
  | {
    callId: string;
    kind: "ANSWER" | "OFFER";
    sdp: string;
    signalId: string;
  }
  | {
    callId: string;
    candidate: string;
    kind: "ICE";
    sdpMid: string | null;
    sdpMLineIndex: number;
    signalId: string;
  };

export function parseDirectCallId(input: unknown): string {
  if (!isRecord(input) || typeof input.callId !== "string" || !DIRECT_CALL_ID_PATTERN.test(input.callId)) {
    throw new Error("Direct call identifier is invalid.");
  }
  return input.callId;
}

export function parseStartDirectCallCommand(input: unknown): {roomId: string} {
  if (!isRecord(input) || typeof input.roomId !== "string" || !DIRECT_ROOM_ID_PATTERN.test(input.roomId)) {
    throw new Error("Direct call room is invalid.");
  }
  return {roomId: input.roomId};
}

export function parseDirectCallResponseCommand(input: unknown): {
  action: "ACCEPT" | "DECLINE";
  callId: string;
} {
  const callId = parseDirectCallId(input);
  if (!isRecord(input) || (input.action !== "ACCEPT" && input.action !== "DECLINE")) {
    throw new Error("Direct call response is invalid.");
  }
  return {action: input.action, callId};
}

export function parseDirectCallSignalCommand(input: unknown): DirectCallSignalCommand {
  const callId = parseDirectCallId(input);
  if (
    !isRecord(input) ||
    typeof input.signalId !== "string" ||
    !DIRECT_CALL_SIGNAL_ID_PATTERN.test(input.signalId)
  ) {
    throw new Error("Direct call signal identifier is invalid.");
  }
  if (input.kind === "OFFER" || input.kind === "ANSWER") {
    if (typeof input.sdp !== "string" || input.sdp.length < 1 || input.sdp.length > 50_000) {
      throw new Error("Direct call session description is invalid.");
    }
    return {callId, kind: input.kind, sdp: input.sdp, signalId: input.signalId};
  }
  if (
    input.kind !== "ICE" ||
    typeof input.candidate !== "string" ||
    input.candidate.length < 1 ||
    input.candidate.length > 4_096 ||
    (input.sdpMid !== null && (typeof input.sdpMid !== "string" || input.sdpMid.length > 64)) ||
    typeof input.sdpMLineIndex !== "number" ||
    !Number.isSafeInteger(input.sdpMLineIndex) ||
    input.sdpMLineIndex < 0 ||
    input.sdpMLineIndex > 64
  ) {
    throw new Error("Direct call ICE candidate is invalid.");
  }
  return {
    callId,
    candidate: input.candidate,
    kind: "ICE",
    sdpMid: input.sdpMid,
    sdpMLineIndex: input.sdpMLineIndex,
    signalId: input.signalId,
  };
}

export function buildDirectCallNotificationData(input: {
  callId: string;
  event: "ENDED" | "INCOMING";
  expiresAtMillis: number;
}): Record<string, string> {
  if (
    !DIRECT_CALL_ID_PATTERN.test(input.callId) ||
    !Number.isSafeInteger(input.expiresAtMillis) ||
    input.expiresAtMillis < 0
  ) {
    throw new Error("Direct call notification is invalid.");
  }
  return {
    callId: input.callId,
    event: input.event,
    expiresAtMillis: String(input.expiresAtMillis),
    type: "SYNAPSE_DIRECT_CALL",
  };
}

export function isDirectCallPointerBusy(
  pointer: {exists: boolean; expiresAtMillis: number | null},
  nowMillis: number,
): boolean {
  if (!pointer.exists) return false;
  return pointer.expiresAtMillis === null || pointer.expiresAtMillis > nowMillis;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
