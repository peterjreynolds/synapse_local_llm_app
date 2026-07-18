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

export function resolveRemoteNotificationSenderDisplayName(
  authorKind: "HUMAN" | "SYNAPSE_AI",
  profileDisplayName: unknown,
): string {
  if (authorKind === "SYNAPSE_AI") return "Synapse";
  if (typeof profileDisplayName !== "string") return "Synapse Chat member";
  const normalized = profileDisplayName.normalize("NFKC").trim();
  return normalized.length >= 1 && normalized.length <= 64 && !/[\u0000-\u001f\u007f]/u.test(normalized) ?
    normalized : "Synapse Chat member";
}

export function buildRemoteMessageNotificationData(input: {
  messageId: string;
  roomId: string;
  senderDisplayName: string;
  senderUid: string;
}): Record<string, string> {
  const senderDisplayName = input.senderDisplayName.normalize("NFKC").trim();
  if (
    senderDisplayName.length === 0 ||
    senderDisplayName.length > 64 ||
    /[\u0000-\u001f\u007f]/u.test(senderDisplayName)
  ) {
    throw new Error("Notification sender display name is invalid.");
  }
  return {
    messageId: input.messageId,
    roomId: input.roomId,
    senderDisplayName,
    senderUid: input.senderUid,
    type: "SYNAPSE_CHAT_MESSAGE",
  };
}

function escapeRegularExpression(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
