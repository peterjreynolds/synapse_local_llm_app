import assert from "node:assert/strict";
import test from "node:test";
import {
  buildDirectRoomIdentity,
  buildNotificationReceiptId,
  parseHumanMessagePayload,
  parseRemoteNotificationMessagePayload,
  parseTargetUid,
} from "./domain.js";

test("builds one deterministic room regardless of caller order", () => {
  const peterFirst = buildDirectRoomIdentity("peter-uid", "trish-uid");
  const trishFirst = buildDirectRoomIdentity("trish-uid", "peter-uid");
  assert.deepEqual(peterFirst, trishFirst);
  assert.match(peterFirst.roomId, /^direct_[a-f0-9]{64}$/);
});

test("refuses self-direct rooms", () => {
  assert.throws(() => buildDirectRoomIdentity("peter-uid", "peter-uid"));
});

test("narrows callable target identifiers", () => {
  assert.equal(parseTargetUid({targetUid: "trish-uid"}), "trish-uid");
  assert.throws(() => parseTargetUid({targetUid: "bad/path"}));
  assert.throws(() => parseTargetUid(null));
});

test("narrows human notification messages", () => {
  assert.deepEqual(
    parseHumanMessagePayload({authorKind: "HUMAN", body: "Hello", senderUid: "peter-uid"}),
    {body: "Hello", senderUid: "peter-uid"},
  );
  assert.throws(() =>
    parseHumanMessagePayload({authorKind: "REMOTE_AI", body: "Hello", senderUid: "ai"}),
  );
  assert.throws(() =>
    parseHumanMessagePayload({authorKind: "HUMAN", body: "", senderUid: "peter-uid"}),
  );
  assert.deepEqual(
    parseHumanMessagePayload({
      attachmentIds: ["attachment-12345678-1234-4123-8123-123456789abc"],
      authorKind: "HUMAN",
      body: "",
      senderUid: "peter-uid",
    }),
    {body: "Attachment", senderUid: "peter-uid"},
  );
});

test("accepts only explicitly attributed AI notification messages", () => {
  assert.deepEqual(
    parseRemoteNotificationMessagePayload({
      aiParticipantId: "participant-synapse-local-ai",
      aiProvenance: "PHONE_LOCAL",
      attachmentIds: [],
      authorKind: "SYNAPSE_AI",
      body: "Local answer",
      senderUid: "participant-synapse-local-ai",
    }),
    {
      authorKind: "SYNAPSE_AI",
      body: "Local answer",
      provenance: "PHONE_LOCAL",
      senderUid: "participant-synapse-local-ai",
    },
  );
  assert.throws(() => parseRemoteNotificationMessagePayload({
    authorKind: "SYNAPSE_AI",
    body: "Forged answer",
    senderUid: "peter-uid",
  }));
});

test("hashes provider event identifiers into safe receipt ids", () => {
  assert.match(buildNotificationReceiptId("provider/event/123"), /^[a-f0-9]{64}$/);
});
