import assert from "node:assert/strict";
import test from "node:test";
import {parseCreateInvitationCommand} from "./invitationAdmin.js";
import {parseReviewRegistrationCommand} from "./registrationReview.js";

test("narrows owner invitation commands", () => {
  assert.deepEqual(
    parseCreateInvitationCommand({
      intendedLabel: " Josh's phone ",
      lifetimeHours: 24,
      maximumUses: 1,
    }),
    {
      intendedLabel: "Josh's phone",
      lifetimeHours: 24,
      maximumUses: 1,
    },
  );
  assert.throws(() =>
    parseCreateInvitationCommand({
      intendedLabel: null,
      lifetimeHours: 0,
      maximumUses: 1,
    }),
  );
});

test("narrows registration review commands", () => {
  assert.deepEqual(
    parseReviewRegistrationCommand({decision: "APPROVE", targetUid: "pending-uid"}),
    {decision: "APPROVE", targetUid: "pending-uid"},
  );
  assert.throws(() =>
    parseReviewRegistrationCommand({decision: "ALLOW", targetUid: "pending-uid"}),
  );
  assert.throws(() =>
    parseReviewRegistrationCommand({decision: "REJECT", targetUid: "../pending"}),
  );
});
