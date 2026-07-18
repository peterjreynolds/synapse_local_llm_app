import assert from "node:assert/strict";
import test from "node:test";
import {
  DAY_MILLIS,
  OPERATIONAL_COLLECTION_GROUP_RETENTION_POLICIES,
  OPERATIONAL_RETENTION_POLICIES,
} from "./operationalRetentionPolicy.js";

test("keeps every operational collection on an explicit bounded retention window", () => {
  assert.deepEqual(
    Object.fromEntries(
      OPERATIONAL_RETENTION_POLICIES.map((policy) => [
        policy.collectionName,
        {days: policy.retentionMillis / DAY_MILLIS, timestampField: policy.timestampField},
      ]),
    ),
    {
      callableRateLimits: {days: 2, timestampField: "windowStartedAt"},
      inviteRedemptions: {days: 180, timestampField: "redeemedAt"},
      invitations: {days: 30, timestampField: "expiresAt"},
      notificationDeliveries: {days: 30, timestampField: "startedAt"},
      callSessions: {days: 30, timestampField: "createdAt"},
      registrationRateLimits: {days: 2, timestampField: "windowStartedAt"},
      registrationReservations: {days: 30, timestampField: "createdAt"},
      remoteAiAuditEvents: {days: 90, timestampField: "createdAt"},
      remoteAiResponseAudits: {days: 90, timestampField: "completedAt"},
      securityAuditEvents: {days: 365, timestampField: "createdAt"},
    },
  );
});

test("keeps direct-call signaling on a bounded collection-group retention window", () => {
  assert.deepEqual(OPERATIONAL_COLLECTION_GROUP_RETENTION_POLICIES, [
    {collectionName: "signals", retentionMillis: 30 * DAY_MILLIS, timestampField: "createdAt"},
  ]);
});

test("retention policies have unique collections and whole-day windows", () => {
  const collectionNames = OPERATIONAL_RETENTION_POLICIES.map((policy) => policy.collectionName);
  assert.equal(new Set(collectionNames).size, collectionNames.length);
  [...OPERATIONAL_RETENTION_POLICIES, ...OPERATIONAL_COLLECTION_GROUP_RETENTION_POLICIES].forEach((policy) => {
    assert.equal(policy.retentionMillis > 0, true);
    assert.equal(policy.retentionMillis % DAY_MILLIS, 0);
    assert.match(policy.timestampField, /^[A-Za-z][A-Za-z0-9]*$/);
  });
});
