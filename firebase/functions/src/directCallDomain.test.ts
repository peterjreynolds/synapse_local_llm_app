import assert from "node:assert/strict";
import test from "node:test";
import {
  buildDirectCallNotificationData,
  DIRECT_CALL_MAXIMUM_RING_CYCLES,
  DIRECT_CALL_RING_CYCLE_MILLIS,
  DIRECT_CALL_RINGING_TIMEOUT_MILLIS,
  isDirectCallPointerBusy,
  parseDirectCallSignalCommand,
  parseStartDirectCallCommand,
} from "./directCallDomain.js";

const callId = `call_${"a".repeat(32)}`;
const roomId = `direct_${"b".repeat(64)}`;

test("narrows video calls while keeping old clients audio-only", () => {
  assert.deepEqual(parseStartDirectCallCommand({roomId}), {mediaKind: "AUDIO", roomId});
  assert.deepEqual(
    parseStartDirectCallCommand({mediaKind: "VIDEO", roomId}),
    {mediaKind: "VIDEO", roomId},
  );
  assert.throws(() => parseStartDirectCallCommand({mediaKind: "SCREEN", roomId}));
});

test("derives the authoritative ringing deadline from twelve complete cycles", () => {
  assert.equal(DIRECT_CALL_RING_CYCLE_MILLIS, 6_000);
  assert.equal(DIRECT_CALL_MAXIMUM_RING_CYCLES, 12);
  assert.equal(DIRECT_CALL_RINGING_TIMEOUT_MILLIS, 72_000);
});

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
      mediaKind: "VIDEO",
    }),
    {
      callId,
      event: "INCOMING",
      expiresAtMillis: "12345",
      mediaKind: "VIDEO",
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
