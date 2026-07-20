import assert from "node:assert/strict";
import test from "node:test";
import {
  buildAttachmentCommandDigest,
  buildAttachmentObjectPath,
  parsePrepareRemoteAttachmentCommand,
} from "./attachmentDomain.js";

const attachmentId = "attachment-12345678-1234-4123-8123-123456789abc";
const messageId = "message-123";
const roomId = `group_${"a".repeat(32)}`;

test("normalizes an allowlisted attachment without using its filename as an object path", () => {
  const command = parsePrepareRemoteAttachmentCommand({
    attachmentId,
    byteCount: 1_024,
    displayName: "../Quarterly report.EXE",
    durationMillis: null,
    kind: "DOCUMENT",
    messageId,
    mimeType: "application/pdf",
    roomId,
  });
  assert.equal(command.displayName, "Quarterly report.pdf");
  assert.equal(
    buildAttachmentObjectPath(roomId, messageId, attachmentId, "content"),
    `roomAttachments/${roomId}/${messageId}/${attachmentId}/content`,
  );
  assert.equal(buildAttachmentCommandDigest(command).length, 64);
});

test("accepts bounded voice notes with explicit duration", () => {
  const command = parsePrepareRemoteAttachmentCommand({
    attachmentId,
    byteCount: 10_240,
    displayName: "Voice note.m4a",
    durationMillis: 12_500,
    kind: "VOICE_NOTE",
    messageId,
    mimeType: "audio/mp4",
    roomId,
  });
  assert.equal(command.kind, "VOICE_NOTE");
  assert.equal(command.durationMillis, 12_500);
});

test("accepts GIF images and normalizes their extension", () => {
  const command = parsePrepareRemoteAttachmentCommand({
    attachmentId,
    byteCount: 2_048,
    displayName: "reaction.not-really-a-jpg",
    durationMillis: null,
    kind: "IMAGE",
    messageId,
    mimeType: "image/gif",
    roomId,
  });
  assert.equal(command.displayName, "reaction.gif");
  assert.equal(command.kind, "IMAGE");
});

test("accepts bounded videos without inventing audio duration", () => {
  const command = parsePrepareRemoteAttachmentCommand({
    attachmentId,
    byteCount: 8 * 1024 * 1024,
    displayName: "launch.clip",
    durationMillis: null,
    kind: "VIDEO",
    messageId,
    mimeType: "video/mp4",
    roomId,
  });
  assert.equal(command.displayName, "launch.mp4");
  assert.equal(command.kind, "VIDEO");
  assert.equal(command.durationMillis, null);
});

test("rejects executables, MIME-kind mismatches, oversize files, and non-random identifiers", () => {
  const base = {
    attachmentId,
    byteCount: 1_024,
    displayName: "payload.apk",
    durationMillis: null,
    kind: "DOCUMENT",
    messageId,
    roomId,
  };
  assert.throws(() => parsePrepareRemoteAttachmentCommand({
    ...base,
    mimeType: "application/vnd.android.package-archive",
  }));
  assert.throws(() => parsePrepareRemoteAttachmentCommand({
    ...base,
    kind: "IMAGE",
    mimeType: "application/pdf",
  }));
  assert.throws(() => parsePrepareRemoteAttachmentCommand({
    ...base,
    byteCount: 26 * 1024 * 1024,
    mimeType: "application/pdf",
  }));
  assert.throws(() => parsePrepareRemoteAttachmentCommand({
    ...base,
    attachmentId: "attachment-predictable",
    mimeType: "application/pdf",
  }));
});
