import assert from "node:assert/strict";
import test from "node:test";
import {
  ACCOUNT_PASSWORD_MINIMUM_LENGTH,
  buildAccountClaims,
  buildRegistrationRateLimitId,
  buildSyntheticAccountEmail,
  digestInvitationCode,
  normalizeUsername,
  parseInviteRegistrationCommand,
  resolveInitialAccountState,
} from "./identity.js";
import {resolveRateLimitSubject} from "./registration.js";

const INVITATION_CODE = "invite_abcdefghijklmnopqrstuvwxyz0123456789";

test("normalizes general account usernames case-insensitively", () => {
  assert.equal(normalizeUsername(" Peter "), "peter");
  assert.equal(buildSyntheticAccountEmail("Josh"), "josh@accounts.synapse.invalid");
});

test("rejects ambiguous username input", () => {
  assert.throws(() => normalizeUsername("Pé ter"));
  assert.throws(() => normalizeUsername("ab"));
  assert.throws(() => normalizeUsername("person@example.com"));
});

test("parses and normalizes invite registration commands", () => {
  const command = parseInviteRegistrationCommand({
    displayName: "  Josh R.  ",
    invitationCode: ` ${INVITATION_CODE} `,
    password: "a secure family password",
    username: " JoSh ",
  });
  assert.deepEqual(command, {
    displayName: "Josh R.",
    invitationCode: INVITATION_CODE,
    password: "a secure family password",
    usernameNormalized: "josh",
  });
});

test("rejects malformed registration boundaries", () => {
  assert.throws(() => parseInviteRegistrationCommand(null));
  assert.throws(() =>
    parseInviteRegistrationCommand({
      displayName: "Josh",
      invitationCode: INVITATION_CODE,
      password: "x".repeat(ACCOUNT_PASSWORD_MINIMUM_LENGTH - 1),
      username: "josh",
    }),
  );
  assert.throws(() =>
    parseInviteRegistrationCommand({
      displayName: "Josh\u0000",
      invitationCode: INVITATION_CODE,
      password: "a secure family password",
      username: "josh",
    }),
  );
  assert.throws(() =>
    parseInviteRegistrationCommand({
      displayName: "Josh",
      invitationCode: "short",
      password: "a secure family password",
      username: "josh",
    }),
  );
});

test("hashes invite and rate-limit material without retaining raw values", () => {
  assert.match(digestInvitationCode(INVITATION_CODE), /^[a-f0-9]{64}$/);
  assert.match(buildRegistrationRateLimitId("203.0.113.8"), /^[a-f0-9]{64}$/);
  assert.notEqual(digestInvitationCode(INVITATION_CODE), INVITATION_CODE);
});

test("fails closed when registration transport identity is unavailable", () => {
  assert.equal(resolveRateLimitSubject(" 203.0.113.8 ", undefined), "203.0.113.8");
  assert.equal(
    resolveRateLimitSubject(undefined, undefined, true),
    "firebase-functions-emulator",
  );
  assert.throws(() => resolveRateLimitSubject(undefined, undefined));
});

test("defaults invited accounts to approval-required state", () => {
  assert.equal(resolveInitialAccountState(undefined), "PENDING_APPROVAL");
  assert.equal(resolveInitialAccountState(true), "PENDING_APPROVAL");
  assert.equal(resolveInitialAccountState(false), "ACTIVE");
  assert.deepEqual(buildAccountClaims("USER", "PENDING_APPROVAL"), {
    accountState: "PENDING_APPROVAL",
    claimsVersion: 1,
    mustChangePassword: false,
    role: "USER",
  });
});
