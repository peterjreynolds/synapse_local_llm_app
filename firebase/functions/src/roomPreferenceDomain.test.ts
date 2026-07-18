import assert from "node:assert/strict";
import test from "node:test";
import {
  isRoomMuteActive,
  parseUpdateRoomPreferencesCommand,
  resolveRoomMuteState,
} from "./roomPreferenceDomain.js";

test("parses bounded direct and group room preference commands", () => {
  const directRoomId = `direct_${"a".repeat(64)}`;
  const groupRoomId = `group_${"b".repeat(32)}`;
  assert.equal(parseUpdateRoomPreferencesCommand({
    archived: false,
    muteDuration: "ONE_HOUR",
    pinned: true,
    roomId: directRoomId,
  }).roomId, directRoomId);
  assert.equal(parseUpdateRoomPreferencesCommand({
    archived: true,
    muteDuration: "FOREVER",
    pinned: false,
    roomId: groupRoomId,
  }).roomId, groupRoomId);
  assert.equal(parseUpdateRoomPreferencesCommand({
    archived: false,
    muteDuration: null,
    pinned: true,
    roomId: groupRoomId,
  }).muteDuration, null);
});

test("rejects unknown mute durations and room identifiers", () => {
  assert.throws(() => parseUpdateRoomPreferencesCommand({
    archived: false,
    muteDuration: "TWO_HOURS",
    pinned: false,
    roomId: "direct_bad",
  }));
});

test("resolves timed, permanent, and disabled mute states", () => {
  assert.deepEqual(resolveRoomMuteState("OFF", 1_000), {muted: false, mutedUntilMillis: null});
  assert.deepEqual(resolveRoomMuteState("FOREVER", 1_000), {muted: true, mutedUntilMillis: null});
  assert.deepEqual(resolveRoomMuteState("ONE_HOUR", 1_000), {
    muted: true,
    mutedUntilMillis: 3_601_000,
  });
  assert.equal(isRoomMuteActive(true, 2_000, 1_999), true);
  assert.equal(isRoomMuteActive(true, 2_000, 2_000), false);
});
