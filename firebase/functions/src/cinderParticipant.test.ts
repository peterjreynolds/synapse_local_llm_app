import assert from "node:assert/strict";
import test from "node:test";
import {Timestamp} from "firebase-admin/firestore";
import {
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_LEGACY_RESPONSE_POLICY,
  CINDER_PARTICIPANT_ID,
} from "./cinderDomain.js";
import {buildCinderParticipantState} from "./cinderParticipant.js";

test("missing participant state is inactive without inventing remote membership", () => {
  const state = buildCinderParticipantState(`direct_${"a".repeat(64)}`, true, undefined);

  assert.equal(state.active, false);
  assert.equal(state.canManage, true);
  assert.equal(state.participantId, CINDER_PARTICIPANT_ID);
  assert.equal(state.mode, "MENTION");
  assert.equal(state.responsePolicy, CINDER_LEGACY_RESPONSE_POLICY);
  assert.equal(state.revision, 0);
});

test("active Cinder participant state requires the exact OpenClaw attribution", () => {
  const now = Timestamp.fromMillis(100);
  const participant = {
    active: true,
    assistantId: CINDER_ASSISTANT_ID,
    createdAt: now,
    displayName: "Cinder",
    kind: "REMOTE_AI",
    mode: "AUTO",
    participantId: CINDER_PARTICIPANT_ID,
    provenance: CINDER_AI_PROVENANCE,
    provider: CINDER_AI_PROVIDER,
    removedAt: null,
    responsePolicy: CINDER_LEGACY_RESPONSE_POLICY,
    updatedAt: now,
  };

  assert.equal(buildCinderParticipantState(`group_${"b".repeat(32)}`, false, participant).active, true);
  assert.equal(buildCinderParticipantState(`group_${"b".repeat(32)}`, false, participant).mode, "AUTO");
  assert.equal(buildCinderParticipantState(`group_${"b".repeat(32)}`, false, participant).revision, 1);
  assert.equal(
    buildCinderParticipantState(
      `group_${"b".repeat(32)}`,
      false,
      {...participant, revision: 7},
    ).revision,
    7,
  );
  assert.throws(
    () => buildCinderParticipantState(`group_${"b".repeat(32)}`, false, {...participant, provider: "generic"}),
    {code: "data-loss"},
  );
  assert.throws(
    () => buildCinderParticipantState(
      `group_${"b".repeat(32)}`,
      false,
      {...participant, revision: 0},
    ),
    {code: "data-loss"},
  );
});
