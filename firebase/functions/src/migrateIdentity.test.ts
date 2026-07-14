import assert from "node:assert/strict";
import test from "node:test";
import {buildIdentityMigrationAccount} from "./migrateIdentity.js";

test("maps the explicit Peter account to the sole owner role", () => {
  assert.deepEqual(
    buildIdentityMigrationAccount({
      disabled: false,
      email: "peter@accounts.synapse.invalid",
      profileAccountState: undefined,
      profileAllowed: true,
      profileExists: true,
      profileMustChangePassword: undefined,
      profileUsernameNormalized: "peter",
      uid: "peter-uid",
    }),
    {
      accountState: "ACTIVE",
      mustChangePassword: false,
      role: "OWNER",
      uid: "peter-uid",
      usernameNormalized: "peter",
    },
  );
});

test("maps other enabled accounts to active users", () => {
  assert.deepEqual(
    buildIdentityMigrationAccount({
      disabled: false,
      email: "josh@accounts.synapse.invalid",
      profileAccountState: undefined,
      profileAllowed: true,
      profileExists: true,
      profileMustChangePassword: true,
      profileUsernameNormalized: "josh",
      uid: "josh-uid",
    }),
    {
      accountState: "ACTIVE",
      mustChangePassword: true,
      role: "USER",
      uid: "josh-uid",
      usernameNormalized: "josh",
    },
  );
});

test("preserves an existing rejected account state", () => {
  assert.deepEqual(
    buildIdentityMigrationAccount({
      disabled: false,
      email: "rejected@accounts.synapse.invalid",
      profileAccountState: "REJECTED",
      profileAllowed: false,
      profileExists: true,
      profileMustChangePassword: false,
      profileUsernameNormalized: "rejected",
      uid: "rejected-uid",
    }),
    {
      accountState: "REJECTED",
      mustChangePassword: false,
      role: "USER",
      uid: "rejected-uid",
      usernameNormalized: "rejected",
    },
  );
});

test("fails closed for missing profiles and inconsistent usernames", () => {
  assert.throws(() =>
    buildIdentityMigrationAccount({
      disabled: false,
      email: "trish@accounts.synapse.invalid",
      profileAccountState: undefined,
      profileAllowed: true,
      profileExists: false,
      profileMustChangePassword: undefined,
      profileUsernameNormalized: undefined,
      uid: "trish-uid",
    }),
  );
  assert.throws(() =>
    buildIdentityMigrationAccount({
      disabled: false,
      email: "trish@accounts.synapse.invalid",
      profileAccountState: undefined,
      profileAllowed: true,
      profileExists: true,
      profileMustChangePassword: undefined,
      profileUsernameNormalized: "someone_else",
      uid: "trish-uid",
    }),
  );
});

test("does not migrate unrelated Firebase identities", () => {
  assert.equal(
    buildIdentityMigrationAccount({
      disabled: false,
      email: "person@example.com",
      profileAccountState: undefined,
      profileAllowed: true,
      profileExists: true,
      profileMustChangePassword: undefined,
      profileUsernameNormalized: "person",
      uid: "person-uid",
    }),
    null,
  );
});
