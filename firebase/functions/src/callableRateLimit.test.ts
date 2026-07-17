import assert from "node:assert/strict";
import test from "node:test";
import {
  CALLABLE_RATE_LIMIT_POLICIES,
  resolveCallableRateLimitDecision,
} from "./callableRateLimit.js";

const policy = {maximumRequests: 2, windowMillis: 1_000};

test("bounds member invitation creation to ten requests per day", () => {
  assert.deepEqual(CALLABLE_RATE_LIMIT_POLICIES.invitationMutation, {
    maximumRequests: 10,
    windowMillis: 24 * 60 * 60_000,
  });
});

test("bounds a callable window and returns a deterministic retry interval", () => {
  const first = resolveCallableRateLimitDecision(policy, null, 10_000);
  assert.deepEqual(first.nextState, {requestCount: 1, windowStartedAtMillis: 10_000});
  const second = resolveCallableRateLimitDecision(policy, first.nextState, 10_100);
  assert.equal(second.allowed, true);
  const denied = resolveCallableRateLimitDecision(policy, second.nextState, 10_200);
  assert.equal(denied.allowed, false);
  assert.equal(denied.retryAfterMillis, 800);
  assert.deepEqual(denied.nextState, second.nextState);
});

test("opens a fresh window without carrying the previous request count", () => {
  assert.deepEqual(
    resolveCallableRateLimitDecision(
      policy,
      {requestCount: 2, windowStartedAtMillis: 10_000},
      11_000,
    ).nextState,
    {requestCount: 1, windowStartedAtMillis: 11_000},
  );
});
