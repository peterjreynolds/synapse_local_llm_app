import {HttpsError} from "firebase-functions/v2/https";

export type RoomMuteDuration = "OFF" | "ONE_HOUR" | "EIGHT_HOURS" | "ONE_WEEK" | "FOREVER";

export interface UpdateRoomPreferencesCommand {
  archived: boolean;
  muteDuration: RoomMuteDuration | null;
  pinned: boolean;
  roomId: string;
}

export interface ResolvedRoomMuteState {
  muted: boolean;
  mutedUntilMillis: number | null;
}

const ROOM_ID_PATTERN = /^(direct_[a-f0-9]{64}|group_[a-f0-9]{32})$/;
const MUTE_DURATION_MILLIS: Readonly<Partial<Record<RoomMuteDuration, number>>> = {
  ONE_HOUR: 60 * 60 * 1000,
  EIGHT_HOURS: 8 * 60 * 60 * 1000,
  ONE_WEEK: 7 * 24 * 60 * 60 * 1000,
};

export function parseUpdateRoomPreferencesCommand(input: unknown): UpdateRoomPreferencesCommand {
  if (
    !isRecord(input) ||
    typeof input.archived !== "boolean" ||
    typeof input.pinned !== "boolean" ||
    typeof input.roomId !== "string" ||
    !ROOM_ID_PATTERN.test(input.roomId) ||
    input.muteDuration !== null && !isRoomMuteDuration(input.muteDuration)
  ) {
    throw new HttpsError("invalid-argument", "Room preference command is invalid.");
  }
  return {
    archived: input.archived,
    muteDuration: input.muteDuration,
    pinned: input.pinned,
    roomId: input.roomId,
  };
}

export function resolveRoomMuteState(
  muteDuration: RoomMuteDuration,
  changedAtMillis: number,
): ResolvedRoomMuteState {
  if (!Number.isSafeInteger(changedAtMillis) || changedAtMillis < 0) {
    throw new Error("Room preference time is invalid.");
  }
  if (muteDuration === "OFF") return {muted: false, mutedUntilMillis: null};
  if (muteDuration === "FOREVER") return {muted: true, mutedUntilMillis: null};
  const durationMillis = MUTE_DURATION_MILLIS[muteDuration];
  if (durationMillis === undefined) throw new Error("Room mute duration is unsupported.");
  return {muted: true, mutedUntilMillis: changedAtMillis + durationMillis};
}

export function isRoomMuteActive(
  muted: unknown,
  mutedUntilMillis: number | null,
  nowMillis: number,
): boolean {
  return muted === true && (mutedUntilMillis === null || mutedUntilMillis > nowMillis);
}

function isRoomMuteDuration(value: unknown): value is RoomMuteDuration {
  return value === "OFF" || value === "ONE_HOUR" || value === "EIGHT_HOURS" ||
    value === "ONE_WEEK" || value === "FOREVER";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
