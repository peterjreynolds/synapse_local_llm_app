import assert from "node:assert/strict";
import test from "node:test";
import {DisabledHostedAiProvider, DISABLED_HOSTED_AI_POLICY} from "./hostedAiProvider.js";
import {
  buildRemoteAiJobId,
  defaultRemoteRoomAiConfiguration,
  digestLocalAiLeaseToken,
  LOCAL_AI_HOST_AVAILABILITY_MILLIS,
  parseCompleteLocalAiResponseCommand,
  parseUpdateRemoteAiConfigurationCommand,
  readRemoteRoomAiConfiguration,
  responsePolicyForConfiguration,
} from "./remoteAiDomain.js";

const roomId = `group_${"a".repeat(32)}`;
const deviceId = "b".repeat(64);

test("narrows local AI room configuration and keeps disabled rooms human-only", () => {
  assert.deepEqual(
    parseUpdateRemoteAiConfigurationCommand({
      hostedAiEnabled: false,
      localAiAutoResponse: false,
      localAiEnabled: true,
      localAiHostDeviceId: deviceId,
      roomId,
    }),
    {
      hostedAiEnabled: false,
      localAiAutoResponse: false,
      localAiEnabled: true,
      localAiHostDeviceId: deviceId,
      roomId,
    },
  );
  assert.throws(() => parseUpdateRemoteAiConfigurationCommand({
    hostedAiEnabled: false,
    localAiAutoResponse: true,
    localAiEnabled: false,
    localAiHostDeviceId: null,
    roomId,
  }));
  assert.equal(defaultRemoteRoomAiConfiguration(roomId).localAiEnabled, false);
});

test("derives designated host availability from a bounded heartbeat", () => {
  const nowMillis = 1_000_000;
  const active = readRemoteRoomAiConfiguration(roomId, {
    hostedAiEnabled: false,
    hostedAiProviderConfigured: false,
    hostedAiStatus: "DISABLED_NO_PROVIDER",
    localAiAutoResponse: true,
    localAiEnabled: true,
    localAiHostDeviceId: deviceId,
    localAiHostLastSeenAtMillis: nowMillis - LOCAL_AI_HOST_AVAILABILITY_MILLIS,
    localAiHostUid: "peter-uid",
  }, nowMillis);
  assert.equal(active.localAiHostAvailable, true);
  assert.equal(readRemoteRoomAiConfiguration(roomId, {
    ...active,
    localAiHostLastSeenAtMillis: nowMillis - LOCAL_AI_HOST_AVAILABILITY_MILLIS - 1,
  }, nowMillis).localAiHostAvailable, false);
});

test("uses deterministic job and opaque lease identities", () => {
  assert.equal(buildRemoteAiJobId(roomId, "message-1"), buildRemoteAiJobId(roomId, "message-1"));
  assert.notEqual(buildRemoteAiJobId(roomId, "message-1"), buildRemoteAiJobId(roomId, "message-2"));
  assert.match(digestLocalAiLeaseToken("x".repeat(32)), /^[a-f0-9]{64}$/);
  assert.equal(parseCompleteLocalAiResponseCommand({
    body: " Answer ",
    deviceId,
    jobId: "c".repeat(64),
    leaseToken: "d".repeat(32),
  }).body, "Answer");
});

test("maps room response settings onto the existing mention or automatic policy", () => {
  assert.equal(responsePolicyForConfiguration(false), "MENTION_ONLY");
  assert.equal(responsePolicyForConfiguration(true), "AUTOMATIC");
});

test("keeps hosted AI disabled without an approved provider or paid budget", async () => {
  assert.deepEqual(DISABLED_HOSTED_AI_POLICY, {
    dailyRoomRequestLimit: 0,
    maximumAttempts: 0,
    maximumMonthlyCostMicrousd: 0,
    timeoutMillis: 30_000,
  });
  await assert.rejects(
    new DisabledHostedAiProvider().generateResponse({
      messages: [],
      policy: DISABLED_HOSTED_AI_POLICY,
      roomId,
      sourceMessageId: "message-1",
    }, new AbortController().signal),
    /Secret Manager/,
  );
});
