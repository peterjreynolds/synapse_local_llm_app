import assert from "node:assert/strict";
import test from "node:test";
import {assertActiveAccountProfile} from "./accountAuthorization.js";

test("Cinder user operations inherit the active allowed account boundary", () => {
  assert.doesNotThrow(() => assertActiveAccountProfile({
    accountState: "ACTIVE",
    allowed: true,
    displayName: "Peter",
    mustChangePassword: false,
    role: "OWNER",
    username: "peter",
  }));
  assert.throws(() => assertActiveAccountProfile({
    accountState: "SUSPENDED",
    allowed: true,
    displayName: "Peter",
    mustChangePassword: false,
    role: "OWNER",
    username: "peter",
  }), {code: "permission-denied"});
  assert.throws(() => assertActiveAccountProfile({
    accountState: "ACTIVE",
    allowed: false,
    displayName: "Peter",
    mustChangePassword: false,
    role: "OWNER",
    username: "peter",
  }), {code: "permission-denied"});
  assert.throws(() => assertActiveAccountProfile({
    accountState: "ACTIVE",
    allowed: true,
    displayName: "Peter",
    mustChangePassword: true,
    role: "OWNER",
    username: "peter",
  }), {code: "permission-denied"});
});
