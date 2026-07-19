import assert from "node:assert/strict";
import test from "node:test";
import {
  buildCinderDirectContentDigest,
  buildCinderJobId,
  buildCinderResponseMessageId,
  canManageCinderParticipant,
  cinderRecordsAfterCursor,
  cinderTimestampSequence,
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_ASSISTANT_ROOM_ID,
  CINDER_PARTICIPANT_ID,
  CINDER_RESPONSE_POLICY,
  digestCinderLeaseToken,
  digestCinderResponseBody,
  hasExplicitCinderMention,
  isCinderHumanRoomQueueEligible,
  isCinderJobClaimable,
  isTrustedCinderRemoteAiMessage,
  MAXIMUM_CINDER_ATTEMPTS,
  parseFailCinderResponseCommand,
  parseSetCinderParticipantCommand,
  parseSubmitCinderMessageCommand,
  parseSyncCinderMessagesCommand,
  shouldRetryCinderFailure,
} from "./cinderDomain.js";

test("normalizes a text-only direct Cinder submission and derives stable identities", () => {
  const command = parseSubmitCinderMessageCommand({
    assistantId: CINDER_ASSISTANT_ID,
    attachmentIds: [],
    body: "  Hello Cinder  ",
    clientCreatedAtMillis: 1_784_435_200_000,
    idempotencyKey: "message-123e4567-e89b-42d3-a456-426614174000",
    messageId: "message-123e4567-e89b-42d3-a456-426614174000",
    replyToMessageId: null,
    roomId: CINDER_ASSISTANT_ROOM_ID,
  });
  const firstJobId = buildCinderJobId({
    accountUid: "peter-uid",
    roomId: command.roomId,
    roomKind: "ASSISTANT",
    sourceMessageId: command.messageId,
  });

  assert.equal(command.body, "Hello Cinder");
  assert.equal(firstJobId.length, 64);
  assert.equal(
    firstJobId,
    buildCinderJobId({
      accountUid: "peter-uid",
      roomId: command.roomId,
      roomKind: "ASSISTANT",
      sourceMessageId: command.messageId,
    }),
  );
  assert.equal(buildCinderResponseMessageId(firstJobId), `cinder-${firstJobId}`);
  assert.equal(buildCinderDirectContentDigest("peter-uid", command).length, 64);
});

test("direct Cinder submissions explicitly reject attachments, replies, and malformed rooms", () => {
  const baseCommand = {
    assistantId: CINDER_ASSISTANT_ID,
    attachmentIds: [],
    body: "Hello",
    clientCreatedAtMillis: 100,
    idempotencyKey: "message-1",
    messageId: "message-1",
    replyToMessageId: null,
    roomId: CINDER_ASSISTANT_ROOM_ID,
  };

  assert.throws(
    () => parseSubmitCinderMessageCommand({...baseCommand, attachmentIds: ["attachment-1"]}),
    {code: "invalid-argument"},
  );
  assert.throws(
    () => parseSubmitCinderMessageCommand({...baseCommand, replyToMessageId: "message-parent"}),
    {code: "invalid-argument"},
  );
  assert.throws(
    () => parseSubmitCinderMessageCommand({...baseCommand, roomId: `direct_${"a".repeat(64)}`}),
    {code: "invalid-argument"},
  );
  assert.throws(
    () => parseSubmitCinderMessageCommand({...baseCommand, clientCreatedAtMillis: Number.MAX_SAFE_INTEGER}),
    {code: "invalid-argument"},
  );
});

test("explicit Cinder mention detection rejects substrings, email text, and lookalike spoofing", () => {
  assert.equal(hasExplicitCinderMention("@Cinder please answer"), true);
  assert.equal(hasExplicitCinderMention("Hi, @cinder: please answer"), true);
  assert.equal(hasExplicitCinderMention("＠Ｃｉｎｄｅｒ please answer"), true);
  assert.equal(hasExplicitCinderMention("@Cinderbox please answer"), false);
  assert.equal(hasExplicitCinderMention("mail@example@cinder"), false);
  assert.equal(hasExplicitCinderMention("@Сinder with a Cyrillic first letter"), false);
  assert.equal(hasExplicitCinderMention("Cinder without an at sign"), false);
});

test("human room queue eligibility requires an active trusted participant and explicit mention", () => {
  const eligibleInput = {
    authorKind: "HUMAN",
    body: "@Cinder summarize this",
    participantActive: true,
    participantId: CINDER_PARTICIPANT_ID,
    participantKind: "REMOTE_AI",
    participantProvenance: CINDER_AI_PROVENANCE,
    responsePolicy: CINDER_RESPONSE_POLICY,
    senderActive: true,
  };

  assert.equal(isCinderHumanRoomQueueEligible(eligibleInput), true);
  assert.equal(isCinderHumanRoomQueueEligible({...eligibleInput, body: "ordinary message"}), false);
  assert.equal(isCinderHumanRoomQueueEligible({...eligibleInput, participantActive: false}), false);
  assert.equal(isCinderHumanRoomQueueEligible({...eligibleInput, senderActive: false}), false);
  assert.equal(isCinderHumanRoomQueueEligible({...eligibleInput, participantId: "forged"}), false);
});

test("participant management follows direct-member and group-administrator permissions", () => {
  assert.equal(canManageCinderParticipant("DIRECT", "MEMBER"), true);
  assert.equal(canManageCinderParticipant("GROUP", "OWNER"), true);
  assert.equal(canManageCinderParticipant("GROUP", "ADMIN"), true);
  assert.equal(canManageCinderParticipant("GROUP", "MEMBER"), false);
  assert.equal(canManageCinderParticipant("ASSISTANT", "OWNER"), false);

  assert.deepEqual(
    parseSetCinderParticipantCommand({active: true, roomId: `group_${"a".repeat(32)}`}),
    {active: true, roomId: `group_${"a".repeat(32)}`},
  );
});

test("claim eligibility recovers expired leases and bounds attempts and retry", () => {
  assert.equal(
    isCinderJobClaimable({attemptCount: 0, leaseExpiresAtMillis: null, nowMillis: 100, state: "PENDING"}),
    true,
  );
  assert.equal(
    isCinderJobClaimable({attemptCount: 1, leaseExpiresAtMillis: 99, nowMillis: 100, state: "CLAIMED"}),
    true,
  );
  assert.equal(
    isCinderJobClaimable({attemptCount: 1, leaseExpiresAtMillis: 101, nowMillis: 100, state: "CLAIMED"}),
    false,
  );
  assert.equal(
    isCinderJobClaimable({
      attemptCount: MAXIMUM_CINDER_ATTEMPTS,
      leaseExpiresAtMillis: null,
      nowMillis: 100,
      state: "PENDING",
    }),
    false,
  );
  assert.equal(shouldRetryCinderFailure(1, true), true);
  assert.equal(shouldRetryCinderFailure(MAXIMUM_CINDER_ATTEMPTS, true), false);
  assert.equal(shouldRetryCinderFailure(1, false), false);
});

test("queue claim and completion replay use stable identities without duplicate work", () => {
  const jobIdentity = {
    accountUid: "peter-uid",
    roomId: `group_${"a".repeat(32)}`,
    roomKind: "GROUP" as const,
    sourceMessageId: "message-1",
  };
  const firstJobId = buildCinderJobId(jobIdentity);
  const replayedJobId = buildCinderJobId(jobIdentity);

  assert.equal(replayedJobId, firstJobId);
  assert.equal(
    isCinderJobClaimable({attemptCount: 1, leaseExpiresAtMillis: 200, nowMillis: 100, state: "CLAIMED"}),
    false,
  );
  assert.equal(
    isCinderJobClaimable({attemptCount: 1, leaseExpiresAtMillis: 100, nowMillis: 100, state: "CLAIMED"}),
    true,
  );
  assert.equal(buildCinderResponseMessageId(firstJobId), buildCinderResponseMessageId(replayedJobId));
  assert.equal(digestCinderResponseBody("Final response"), digestCinderResponseBody("Final response"));
  assert.notEqual(digestCinderResponseBody("Final response"), digestCinderResponseBody("Different response"));
});

test("worker failure commands and lease digests narrow sensitive lease material", () => {
  const command = parseFailCinderResponseCommand({
    failureCode: "OPENCLAW_UNAVAILABLE",
    jobId: "a".repeat(64),
    leaseId: "123e4567-e89b-42d3-a456-426614174000",
    leaseToken: "x".repeat(43),
    retryable: true,
    workerId: "openclaw-cinder-1",
  });

  assert.equal(command.retryable, true);
  assert.equal(digestCinderLeaseToken(command.leaseToken).length, 64);
  assert.notEqual(digestCinderLeaseToken(command.leaseToken), command.leaseToken);
  assert.throws(
    () => parseFailCinderResponseCommand({...command, failureCode: "UNKNOWN"}),
    {code: "invalid-argument"},
  );
});

test("server sequence and cursor ordering never use client timestamps", () => {
  const sequence = cinderTimestampSequence(1_784_435_200, 123_456_789);
  assert.equal(sequence, 1_784_435_200_123_456);
  assert.deepEqual(
    cinderRecordsAfterCursor(
      [{id: "third", sequence: 3}, {id: "first", sequence: 1}, {id: "second", sequence: 2}],
      1,
      2,
    ),
    [{id: "second", sequence: 2}, {id: "third", sequence: 3}],
  );
  assert.deepEqual(parseSyncCinderMessagesCommand({afterSequence: 7, limit: 25}), {
    afterSequence: 7,
    limit: 25,
  });
});

test("only the exact OpenClaw Cinder remote AI attribution is trusted", () => {
  const trustedMessage = {
    aiParticipantId: CINDER_PARTICIPANT_ID,
    aiProvenance: CINDER_AI_PROVENANCE,
    aiProvider: CINDER_AI_PROVIDER,
    assistantId: CINDER_ASSISTANT_ID,
    authorKind: "REMOTE_AI",
    senderUid: CINDER_PARTICIPANT_ID,
  };

  assert.equal(isTrustedCinderRemoteAiMessage(trustedMessage), true);
  assert.equal(isTrustedCinderRemoteAiMessage({...trustedMessage, senderUid: "peter-uid"}), false);
  assert.equal(isTrustedCinderRemoteAiMessage({...trustedMessage, aiProvider: "generic"}), false);
  assert.equal(isTrustedCinderRemoteAiMessage({...trustedMessage, authorKind: "HUMAN"}), false);
});
