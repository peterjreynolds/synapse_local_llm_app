import assert from "node:assert/strict";
import test from "node:test";
import {diagnoseActiveRoomIntegrity} from "./ownerOperations.js";

test("accepts a consistent direct-room membership projection", () => {
  assert.deepEqual(
    diagnoseActiveRoomIntegrity(
      {activeMemberIds: ["peter", "trish"], kind: "DIRECT", memberIds: ["peter", "trish"]},
      new Map([
        ["peter", {active: true, role: "MEMBER", uid: "peter"}],
        ["trish", {active: true, role: "MEMBER", uid: "trish"}],
      ]),
    ),
    [],
  );
});

test("reports bounded issue codes for inconsistent room and membership state", () => {
  assert.deepEqual(
    diagnoseActiveRoomIntegrity(
      {
        activeMemberIds: ["peter", "missing"],
        kind: "GROUP",
        memberIds: ["peter"],
        ownerUid: "missing",
      },
      new Map([
        ["peter", {active: false, role: "MEMBER", uid: "somebody-else"}],
      ]),
    ),
    [
      "ACTIVE_MEMBERSHIP_INACTIVE",
      "ACTIVE_MEMBERSHIP_MISSING",
      "ACTIVE_MEMBER_NOT_IN_HISTORY",
      "GROUP_OWNER_ROLE_INVALID",
      "MEMBERSHIP_UID_MISMATCH",
    ],
  );
});
