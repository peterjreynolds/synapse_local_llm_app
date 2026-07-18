import {HttpsError} from "firebase-functions/v2/https";

export const MAXIMUM_GROUP_MEMBERS = 20;
export const GROUP_ROOM_ID_PATTERN = /^group_[a-f0-9]{32}$/;
export type GroupMemberRole = "OWNER" | "ADMIN" | "MEMBER";

export interface CreateGroupRoomCommand {
  memberUids: string[];
  title: string;
}

export interface GroupMemberCommand {
  roomId: string;
  targetUid: string;
}

export interface SetGroupMemberRoleCommand extends GroupMemberCommand {
  role: "ADMIN" | "MEMBER";
}

export interface UpdateGroupPreferencesCommand {
  archived: boolean;
  muted: boolean;
  pinned: boolean;
  roomId: string;
}

export function parseCreateGroupRoomCommand(input: unknown): CreateGroupRoomCommand {
  if (!isRecord(input) || !Array.isArray(input.memberUids) || typeof input.title !== "string") {
    invalidGroupCommand();
  }
  const memberUids = input.memberUids.map(parseGroupAccountUid);
  if (
    memberUids.length === 0 ||
    memberUids.length >= MAXIMUM_GROUP_MEMBERS ||
    new Set(memberUids).size !== memberUids.length
  ) {
    invalidGroupCommand();
  }
  return {memberUids, title: normalizeGroupTitle(input.title)};
}

export function parseGroupRoomCommand(input: unknown): {roomId: string} {
  if (!isRecord(input) || typeof input.roomId !== "string") invalidGroupCommand();
  return {roomId: parseGroupRoomId(input.roomId)};
}

export function parseGroupMemberCommand(input: unknown): GroupMemberCommand {
  if (!isRecord(input) || typeof input.targetUid !== "string") invalidGroupCommand();
  return {
    roomId: parseGroupRoomId(input.roomId),
    targetUid: parseGroupAccountUid(input.targetUid),
  };
}

export function parseGroupMemberListCommand(input: unknown): {memberUids: string[]; roomId: string} {
  if (!isRecord(input) || !Array.isArray(input.memberUids)) invalidGroupCommand();
  const memberUids = input.memberUids.map(parseGroupAccountUid);
  if (
    memberUids.length === 0 ||
    memberUids.length >= MAXIMUM_GROUP_MEMBERS ||
    new Set(memberUids).size !== memberUids.length
  ) {
    invalidGroupCommand();
  }
  return {memberUids, roomId: parseGroupRoomId(input.roomId)};
}

export function parseSetGroupMemberRoleCommand(input: unknown): SetGroupMemberRoleCommand {
  const command = parseGroupMemberCommand(input);
  if (!isRecord(input) || (input.role !== "ADMIN" && input.role !== "MEMBER")) {
    invalidGroupCommand();
  }
  return {...command, role: input.role};
}

export function parseRenameGroupRoomCommand(input: unknown): {roomId: string; title: string} {
  if (!isRecord(input) || typeof input.title !== "string") invalidGroupCommand();
  return {
    roomId: parseGroupRoomId(input.roomId),
    title: normalizeGroupTitle(input.title),
  };
}

export function parseSetGroupAvatarCommand(
  input: unknown,
): {avatarObjectPath: string | null; roomId: string} {
  if (!isRecord(input) || (input.avatarObjectPath !== null && typeof input.avatarObjectPath !== "string")) {
    invalidGroupCommand();
  }
  const roomId = parseGroupRoomId(input.roomId);
  const avatarObjectPath = input.avatarObjectPath;
  if (
    typeof avatarObjectPath === "string" &&
    !new RegExp(`^groupAvatars/${roomId}/avatar_[a-f0-9]{32}\\.(jpg|png|webp)$`).test(avatarObjectPath)
  ) {
    invalidGroupCommand();
  }
  return {avatarObjectPath, roomId};
}

export function parseUpdateGroupPreferencesCommand(input: unknown): UpdateGroupPreferencesCommand {
  if (
    !isRecord(input) ||
    typeof input.archived !== "boolean" ||
    typeof input.muted !== "boolean" ||
    typeof input.pinned !== "boolean"
  ) {
    invalidGroupCommand();
  }
  return {
    archived: input.archived,
    muted: input.muted,
    pinned: input.pinned,
    roomId: parseGroupRoomId(input.roomId),
  };
}

export function parseDeleteGroupRoomCommand(input: unknown): {confirmTitle: string; roomId: string} {
  if (!isRecord(input) || typeof input.confirmTitle !== "string") invalidGroupCommand();
  return {
    confirmTitle: input.confirmTitle.trim(),
    roomId: parseGroupRoomId(input.roomId),
  };
}

export function parseGroupRoomId(value: unknown): string {
  if (typeof value !== "string" || !GROUP_ROOM_ID_PATTERN.test(value)) invalidGroupCommand();
  return value;
}

export function parseGroupAccountUid(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) invalidGroupCommand();
  return value;
}

export function normalizeGroupTitle(value: string): string {
  const normalized = value.normalize("NFKC").trim();
  if (normalized.length === 0 || normalized.length > 80 || /[\u0000-\u001f\u007f]/.test(normalized)) {
    invalidGroupCommand();
  }
  return normalized;
}

function invalidGroupCommand(): never {
  throw new HttpsError("invalid-argument", "Group command is invalid.");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
