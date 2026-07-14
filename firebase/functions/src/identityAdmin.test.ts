import assert from "node:assert/strict";
import test from "node:test";
import {parseCreateInvitationCommand} from "./invitationAdmin.js";
import {parseCreateOwnerAccountCommand} from "./ownerAccountMutation.js";
import {parseOwnerPasswordResetCommand} from "./passwordAdmin.js";
import {isRecentAuthentication} from "./ownerAuthorization.js";
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

test("narrows direct owner account creation without retaining password output", () => {
  assert.deepEqual(
    parseCreateOwnerAccountCommand({
      displayName: " Josh R. ",
      password: "temporary family password",
      requirePasswordChange: true,
      username: " JoSh ",
    }),
    {
      displayName: "Josh R.",
      password: "temporary family password",
      requirePasswordChange: true,
      usernameNormalized: "josh",
    },
  );
  assert.throws(() =>
    parseCreateOwnerAccountCommand({
      displayName: "Josh",
      password: "short",
      requirePasswordChange: true,
      username: "josh",
    }),
  );
});

test("narrows password reset commands", () => {
  assert.deepEqual(
    parseOwnerPasswordResetCommand({
      password: "another temporary password",
      requirePasswordChange: true,
      targetUid: "target-uid",
    }),
    {
      password: "another temporary password",
      requirePasswordChange: true,
      targetUid: "target-uid",
    },
  );
});

test("rejects stale or future owner authentication times", () => {
  assert.equal(isRecentAuthentication(1_000, 1_200, 300), true);
  assert.equal(isRecentAuthentication(1_000, 1_301, 300), false);
  assert.equal(isRecentAuthentication(1_400, 1_200, 300), false);
  assert.equal(isRecentAuthentication("1000", 1_200, 300), false);
});
