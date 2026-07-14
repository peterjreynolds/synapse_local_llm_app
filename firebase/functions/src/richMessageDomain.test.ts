import assert from "node:assert/strict";
import test from "node:test";
import {
  buildMessageMutationReceiptId,
  buildReactionId,
  parseAcknowledgeRemoteMessagesCommand,
  parseEditRemoteMessageCommand,
  parseSendRemoteMessageCommand,
  parseToggleRemoteReactionCommand,
} from "./richMessageDomain.js";

const roomId = `group_${"a".repeat(32)}`;

test("normalizes bounded send and edit commands", () => {
  assert.deepEqual(
    parseSendRemoteMessageCommand({
      body: "  Hello  ",
      clientCreatedAtMillis: 1_000,
      messageId: "message-1",
      replyToMessageId: null,
      roomId,
    }),
    {
      body: "Hello",
      clientCreatedAtMillis: 1_000,
      messageId: "message-1",
      replyToMessageId: null,
      roomId,
    },
  );
  assert.equal(
    parseEditRemoteMessageCommand({
      body: "Edited",
      expectedRevision: 2,
      messageId: "message-1",
      mutationId: "mutation-edit-0001",
      roomId,
    }).body,
    "Edited",
  );
});

test("rejects invalid message bodies, revisions, identifiers, and acknowledgements", () => {
  assert.throws(() => parseSendRemoteMessageCommand({
    body: "",
    clientCreatedAtMillis: 1_000,
    messageId: "message-1",
    replyToMessageId: null,
    roomId,
  }));
  assert.throws(() => parseEditRemoteMessageCommand({
    body: "Edited",
    expectedRevision: 0,
    messageId: "message-1",
    mutationId: "short",
    roomId,
  }));
  assert.throws(() => parseAcknowledgeRemoteMessagesCommand({
    messageIds: ["message-1", "message-1"],
    read: true,
    roomId,
  }));
});

test("normalizes reactions and derives stable opaque identifiers", () => {
  assert.deepEqual(
    parseToggleRemoteReactionCommand({
      emoji: "👍",
      messageId: "message-1",
      reacted: true,
      roomId,
    }),
    {emoji: "👍", messageId: "message-1", reacted: true, roomId},
  );
  assert.equal(buildReactionId("peter-uid", "👍"), buildReactionId("peter-uid", "👍"));
  assert.notEqual(buildReactionId("peter-uid", "👍"), buildReactionId("trish-uid", "👍"));
  assert.equal(
    buildMessageMutationReceiptId("peter-uid", "EDIT", "mutation-edit-0001").length,
    64,
  );
});
