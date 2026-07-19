import assert from "node:assert/strict";
import test from "node:test";
import {createCinderWorkerAuthorizationVerifier} from "./cinderWorkerAuthentication.js";

test("worker authentication accepts only the configured bearer secret", () => {
  const secret = "server-only-cinder-worker-secret-1234567890";
  const verify = createCinderWorkerAuthorizationVerifier(secret);

  assert.equal(verify(`Bearer ${secret}`), true);
  assert.equal(verify("Bearer server-only-cinder-worker-secret-wrong"), false);
  assert.equal(verify(`Basic ${secret}`), false);
  assert.equal(verify(undefined), false);
});

test("worker authentication rejects weak configuration before serving requests", () => {
  assert.throws(
    () => createCinderWorkerAuthorizationVerifier("short"),
    /32-512 visible ASCII/,
  );
});
