import assert from "node:assert/strict";
import test from "node:test";
import {buildDirectRoomIdentity} from "./domain.js";
import {
  assertActiveDirectRoomMembership,
  buildLegacyDirectRoomRepairPlan,
} from "./repairLegacyDirectRooms.js";

const identity = buildDirectRoomIdentity("peter-uid", "trish-uid");

test("backfills the server fields omitted from legacy direct rooms", () => {
  assert.deepEqual(
    buildLegacyDirectRoomRepairPlan(identity.roomId, {
      activeMemberIds: identity.memberIds,
      directKey: identity.directKey,
      kind: "DIRECT",
      memberIds: identity.memberIds,
    }),
    {
      memberIds: identity.memberIds,
      patch: {
        avatarObjectPath: null,
        deletedAt: null,
        ownerUid: null,
        revision: 1,
      },
    },
  );
});

test("does not rewrite complete or deleted direct rooms", () => {
  const completeRoom = {
    activeMemberIds: identity.memberIds,
    avatarObjectPath: null,
    deletedAt: null,
    directKey: identity.directKey,
    kind: "DIRECT",
    memberIds: identity.memberIds,
    ownerUid: null,
    revision: 1,
  };
  assert.equal(buildLegacyDirectRoomRepairPlan(identity.roomId, completeRoom), null);
  assert.equal(
    buildLegacyDirectRoomRepairPlan(identity.roomId, {...completeRoom, deletedAt: {seconds: 1}}),
    null,
  );
});

test("fails closed for inconsistent direct-room identity or membership", () => {
  assert.throws(() =>
    buildLegacyDirectRoomRepairPlan(identity.roomId, {
      activeMemberIds: ["peter-uid", "intruder-uid"],
      directKey: identity.directKey,
      kind: "DIRECT",
      memberIds: identity.memberIds,
    }),
  );
  assert.throws(() =>
    assertActiveDirectRoomMembership(identity.roomId, "peter-uid", {
      active: true,
      role: "ADMIN",
      uid: "peter-uid",
    }),
  );
  assert.doesNotThrow(() =>
    assertActiveDirectRoomMembership(identity.roomId, "peter-uid", {
      active: true,
      role: "MEMBER",
      uid: "peter-uid",
    }),
  );
});
