import assert from "node:assert/strict";
import test from "node:test";
import {
  buildRemoteMessageNotificationData,
  DEFAULT_NOTIFICATION_PREFERENCES,
  nextRemoteNotificationUnreadCount,
  readNotificationPreferences,
  shouldNotifyForRemoteMessage,
} from "./notificationPreferenceDomain.js";

test("uses defaults for missing documents and fails closed for malformed documents", () => {
  assert.deepEqual(readNotificationPreferences(undefined), DEFAULT_NOTIFICATION_PREFERENCES);
  assert.deepEqual(readNotificationPreferences({directMessages: "yes"}), {
    directMessages: false,
    groupMessages: false,
    mentions: false,
    mutedRooms: false,
  });
  assert.equal(DEFAULT_NOTIFICATION_PREFERENCES.mutedRooms, false);
});

test("supports direct, group, mention, and muted-room preferences", () => {
  const preferences = {
    directMessages: false,
    groupMessages: false,
    mentions: true,
    mutedRooms: false,
  };
  assert.equal(shouldNotifyForRemoteMessage({
    body: "hello",
    muted: false,
    preferences,
    recipientUsername: "trish",
    roomKind: "DIRECT",
  }), false);
  assert.equal(shouldNotifyForRemoteMessage({
    body: "Please ask @Trish about this",
    muted: false,
    preferences,
    recipientUsername: "trish",
    roomKind: "GROUP",
  }), true);
  assert.equal(shouldNotifyForRemoteMessage({
    body: "Please ask @Trish about this",
    muted: true,
    preferences,
    recipientUsername: "trish",
    roomKind: "GROUP",
  }), false);
});

test("notification routing payload never includes message plaintext", () => {
  const data = buildRemoteMessageNotificationData({
    messageId: "message-1",
    roomId: `direct_${"a".repeat(64)}`,
    senderUid: "peter",
    unreadCount: 3,
  });
  assert.deepEqual(
    Object.keys(data).sort(),
    ["messageId", "roomId", "senderUid", "type", "unreadCount"],
  );
  assert.equal(data.unreadCount, "3");
  assert.equal("body" in data, false);
  assert.equal("senderDisplayName" in data, false);
});

test("notification unread counts are bounded for launcher badges", () => {
  assert.equal(nextRemoteNotificationUnreadCount(undefined), 1);
  assert.equal(nextRemoteNotificationUnreadCount(-10), 1);
  assert.equal(nextRemoteNotificationUnreadCount(7), 8);
  assert.equal(nextRemoteNotificationUnreadCount(999), 999);
  assert.throws(() => buildRemoteMessageNotificationData({
    messageId: "message-1",
    roomId: `direct_${"a".repeat(64)}`,
    senderUid: "trish",
    unreadCount: 0,
  }));
});
