import assert from "node:assert/strict";
import test from "node:test";
import {selectAuthorizedMessageRecipientUids} from "./recipientAuthorization.js";

test("selects only recipients with allowed profiles and active memberships", () => {
  const selected = selectAuthorizedMessageRecipientUids(
    ["allowed-active", "deallowed", "inactive-member", "missing-state"],
    [
      {membershipActive: true, profileAllowed: true, uid: "allowed-active"},
      {membershipActive: true, profileAllowed: false, uid: "deallowed"},
      {membershipActive: false, profileAllowed: true, uid: "inactive-member"},
    ],
  );

  assert.deepEqual(selected, ["allowed-active"]);
});

test("deduplicates candidate indexes without dropping an authorized unread recipient", () => {
  const selected = selectAuthorizedMessageRecipientUids(
    ["authorized-without-device", "authorized-without-device"],
    [
      {
        membershipActive: true,
        profileAllowed: true,
        uid: "authorized-without-device",
      },
    ],
  );

  assert.deepEqual(selected, ["authorized-without-device"]);
});

test("fails closed for missing authorization state", () => {
  assert.deepEqual(selectAuthorizedMessageRecipientUids(["missing"], []), []);
});
