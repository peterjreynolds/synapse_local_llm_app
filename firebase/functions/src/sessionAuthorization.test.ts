import assert from "node:assert/strict";
import test from "node:test";
import {Timestamp} from "firebase-admin/firestore";
import {assertSessionNotRevoked} from "./sessionAuthorization.js";

test("accepts a valid session when an account has no revocation marker", () => {
  assert.doesNotThrow(() => assertSessionNotRevoked(100, undefined));
});

test("rejects sessions at or before the persisted revocation second", () => {
  const revokedAt = Timestamp.fromMillis(100_900);
  assert.throws(() => assertSessionNotRevoked(99, revokedAt), {code: "permission-denied"});
  assert.throws(() => assertSessionNotRevoked(100, revokedAt), {code: "permission-denied"});
  assert.doesNotThrow(() => assertSessionNotRevoked(101, revokedAt));
});

test("fails closed for malformed authentication and revocation state", () => {
  assert.throws(() => assertSessionNotRevoked("100", undefined), {code: "unauthenticated"});
  assert.throws(() => assertSessionNotRevoked(100, null), {code: "permission-denied"});
});
