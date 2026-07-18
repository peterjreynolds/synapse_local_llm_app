import {HttpsError} from "firebase-functions/v2/https";

export interface NotificationPreferences {
  directMessages: boolean;
  groupMessages: boolean;
  mentions: boolean;
  mutedRooms: boolean;
}

export const DEFAULT_NOTIFICATION_PREFERENCES: Readonly<NotificationPreferences> = {
  directMessages: true,
  groupMessages: true,
  mentions: true,
  mutedRooms: false,
};

const DISABLED_NOTIFICATION_PREFERENCES: Readonly<NotificationPreferences> = {
  directMessages: false,
  groupMessages: false,
  mentions: false,
  mutedRooms: false,
};

export function parseNotificationPreferences(input: unknown): NotificationPreferences {
  if (
    !isRecord(input) ||
    typeof input.directMessages !== "boolean" ||
    typeof input.groupMessages !== "boolean" ||
    typeof input.mentions !== "boolean" ||
    typeof input.mutedRooms !== "boolean"
  ) {
    throw new HttpsError("invalid-argument", "Notification preferences are invalid.");
  }
  return {
    directMessages: input.directMessages,
    groupMessages: input.groupMessages,
    mentions: input.mentions,
    mutedRooms: input.mutedRooms,
  };
}

export function readNotificationPreferences(input: unknown): NotificationPreferences {
  if (input === undefined) return {...DEFAULT_NOTIFICATION_PREFERENCES};
  try {
    return parseNotificationPreferences(input);
  } catch {
    return {...DISABLED_NOTIFICATION_PREFERENCES};
  }
}

export function shouldNotifyForRemoteMessage(input: {
  body: string;
  muted: boolean;
  preferences: NotificationPreferences;
  recipientUsername: string;
  roomKind: "DIRECT" | "GROUP";
}): boolean {
  if (input.muted && !input.preferences.mutedRooms) return false;
  if (input.roomKind === "DIRECT") return input.preferences.directMessages;
  const normalizedBody = input.body.normalize("NFKC").toLocaleLowerCase("en-US");
  const normalizedUsername = input.recipientUsername.normalize("NFKC").toLocaleLowerCase("en-US");
  const mentioned = normalizedUsername.length > 0 && new RegExp(
    `(^|[^\\p{L}\\p{N}_])@${escapeRegularExpression(normalizedUsername)}($|[^\\p{L}\\p{N}_])`,
    "u",
  ).test(normalizedBody);
  return input.preferences.groupMessages || (input.preferences.mentions && mentioned);
}

export function buildRemoteMessageNotificationData(input: {
  messageId: string;
  roomId: string;
  senderUid: string;
  unreadCount: number;
}): Record<string, string> {
  if (
    !Number.isSafeInteger(input.unreadCount) ||
    input.unreadCount < 1 ||
    input.unreadCount > MAXIMUM_NOTIFICATION_UNREAD_COUNT
  ) {
    throw new Error("Notification unread count is invalid.");
  }
  return {
    messageId: input.messageId,
    roomId: input.roomId,
    senderUid: input.senderUid,
    type: "SYNAPSE_CHAT_MESSAGE",
    unreadCount: String(input.unreadCount),
  };
}

export function nextRemoteNotificationUnreadCount(currentUnreadCount: unknown): number {
  const parsedUnreadCount = typeof currentUnreadCount === "number" &&
    Number.isSafeInteger(currentUnreadCount) &&
    currentUnreadCount >= 0 ? currentUnreadCount : 0;
  return Math.min(parsedUnreadCount + 1, MAXIMUM_NOTIFICATION_UNREAD_COUNT);
}

function escapeRegularExpression(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export const MAXIMUM_NOTIFICATION_UNREAD_COUNT = 999;
