import assert from "node:assert/strict";
import test from "node:test";
import {
  normalizeGroupTitle,
  parseCreateGroupRoomCommand,
  parseSetGroupAvatarCommand,
  parseSetGroupMemberRoleCommand,
} from "./groupRoomDomain.js";

test("normalizes bounded group creation commands", () => {
  assert.deepEqual(
    parseCreateGroupRoomCommand({memberUids: ["trish-uid", "josh-uid"], title: " Family Chat "}),
    {memberUids: ["trish-uid", "josh-uid"], title: "Family Chat"},
  );
  assert.throws(() => parseCreateGroupRoomCommand({memberUids: [], title: "Empty"}));
  assert.throws(() => parseCreateGroupRoomCommand({memberUids: ["same", "same"], title: "Duplicate"}));
  assert.throws(() => normalizeGroupTitle("\u0000unsafe"));
});

test("narrows group roles and avatar object paths", () => {
  const roomId = `group_${"a".repeat(32)}`;
  assert.deepEqual(
    parseSetGroupMemberRoleCommand({role: "ADMIN", roomId, targetUid: "trish-uid"}),
    {role: "ADMIN", roomId, targetUid: "trish-uid"},
  );
  assert.throws(() => parseSetGroupMemberRoleCommand({role: "OWNER", roomId, targetUid: "trish-uid"}));
  assert.deepEqual(
    parseSetGroupAvatarCommand({
      avatarObjectPath: `groupAvatars/${roomId}/avatar_${"b".repeat(32)}.webp`,
      roomId,
    }),
    {
      avatarObjectPath: `groupAvatars/${roomId}/avatar_${"b".repeat(32)}.webp`,
      roomId,
    },
  );
  assert.throws(() => parseSetGroupAvatarCommand({avatarObjectPath: "avatars/private", roomId}));
});
