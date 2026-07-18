import assert from "node:assert/strict";
import test from "node:test";
import {
  authorizeInvitationCreation,
  parseCreateInvitationCommand,
} from "./invitationAdmin.js";
import {parseCreateOwnerAccountCommand} from "./ownerAccountMutation.js";
import {parseOwnerPasswordResetCommand} from "./passwordAdmin.js";
import {isRecentAuthentication} from "./ownerAuthorization.js";
import {parseReviewRegistrationCommand} from "./registrationReview.js";

test("narrows invitation commands and bounds non-owner invitations", () => {
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
  assert.doesNotThrow(() =>
    authorizeInvitationCreation("USER", {
      intendedLabel: null,
      lifetimeHours: 24 * 7,
      maximumUses: 1,
    }),
  );
  assert.throws(() =>
    authorizeInvitationCreation("USER", {
      intendedLabel: null,
      lifetimeHours: 24 * 7,
      maximumUses: 2,
    }),
  );
  assert.throws(() =>
    authorizeInvitationCreation("ADMIN", {
      intendedLabel: null,
      lifetimeHours: 24 * 7 + 1,
      maximumUses: 1,
    }),
  );
  assert.doesNotThrow(() =>
    authorizeInvitationCreation("OWNER", {
      intendedLabel: "Family",
      lifetimeHours: 24 * 30,
      maximumUses: 100,
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
