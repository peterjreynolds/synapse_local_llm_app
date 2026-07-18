import assert from "node:assert/strict";
import test from "node:test";
import {
  buildDirectCallNotificationData,
  isDirectCallPointerBusy,
  parseDirectCallSignalCommand,
} from "./directCallDomain.js";

const callId = `call_${"a".repeat(32)}`;

test("narrows offer and ICE call signals", () => {
  assert.deepEqual(
    parseDirectCallSignalCommand({
      callId,
      kind: "OFFER",
      sdp: "v=0\r\n",
      signalId: `signal_${"b".repeat(32)}`,
    }),
    {
      callId,
      kind: "OFFER",
      sdp: "v=0\r\n",
      signalId: `signal_${"b".repeat(32)}`,
    },
  );
  assert.equal(
    parseDirectCallSignalCommand({
      callId,
      candidate: "candidate:1 1 UDP 1 192.0.2.1 5000 typ host",
      kind: "ICE",
      sdpMid: "0",
      sdpMLineIndex: 0,
      signalId: `signal_${"c".repeat(32)}`,
    }).kind,
    "ICE",
  );
  assert.throws(() => parseDirectCallSignalCommand({
    callId,
    candidate: "",
    kind: "ICE",
    sdpMid: "0",
    sdpMLineIndex: -1,
    signalId: `signal_${"d".repeat(32)}`,
  }));
});

test("builds private call routing data without names or message text", () => {
  assert.deepEqual(
    buildDirectCallNotificationData({
      callId,
      event: "INCOMING",
      expiresAtMillis: 12_345,
    }),
    {
      callId,
      event: "INCOMING",
      expiresAtMillis: "12345",
      type: "SYNAPSE_DIRECT_CALL",
    },
  );
});

test("fails closed for malformed active call pointers", () => {
  assert.equal(isDirectCallPointerBusy({exists: false, expiresAtMillis: null}, 1_000), false);
  assert.equal(isDirectCallPointerBusy({exists: true, expiresAtMillis: null}, 1_000), true);
  assert.equal(isDirectCallPointerBusy({exists: true, expiresAtMillis: 999}, 1_000), false);
  assert.equal(isDirectCallPointerBusy({exists: true, expiresAtMillis: 1_001}, 1_000), true);
});
