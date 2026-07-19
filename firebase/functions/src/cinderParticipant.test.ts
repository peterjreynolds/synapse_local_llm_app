import assert from "node:assert/strict";
import test from "node:test";
import {Timestamp} from "firebase-admin/firestore";
import {
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_PARTICIPANT_ID,
  CINDER_RESPONSE_POLICY,
} from "./cinderDomain.js";
import {buildCinderParticipantState} from "./cinderParticipant.js";

test("missing participant state is inactive without inventing remote membership", () => {
  const state = buildCinderParticipantState(`direct_${"a".repeat(64)}`, true, undefined);

  assert.equal(state.active, false);
  assert.equal(state.canManage, true);
  assert.equal(state.participantId, CINDER_PARTICIPANT_ID);
  assert.equal(state.responsePolicy, CINDER_RESPONSE_POLICY);
});

test("active Cinder participant state requires the exact OpenClaw attribution", () => {
  const now = Timestamp.fromMillis(100);
  const participant = {
    active: true,
    assistantId: CINDER_ASSISTANT_ID,
    createdAt: now,
    displayName: "Cinder",
    kind: "REMOTE_AI",
    participantId: CINDER_PARTICIPANT_ID,
    provenance: CINDER_AI_PROVENANCE,
    provider: CINDER_AI_PROVIDER,
    removedAt: null,
    responsePolicy: CINDER_RESPONSE_POLICY,
    updatedAt: now,
  };

  assert.equal(buildCinderParticipantState(`group_${"b".repeat(32)}`, false, participant).active, true);
  assert.throws(
    () => buildCinderParticipantState(`group_${"b".repeat(32)}`, false, {...participant, provider: "generic"}),
    {code: "data-loss"},
  );
});
