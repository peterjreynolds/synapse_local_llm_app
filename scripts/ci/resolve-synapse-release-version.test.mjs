import assert from "node:assert/strict";
import test from "node:test";
import {resolveSynapseReleaseVersion} from "./resolve-synapse-release-version.mjs";

test("uses the workflow sequence when no rolling release exists", () => {
  assert.deepEqual(
    resolveSynapseReleaseVersion({workflowRunNumber: "18", currentReleaseBody: null}),
    {versionCode: 2018, versionName: "0.1.2018"},
  );
});

test("increments a manually published release that is ahead of the workflow sequence", () => {
  assert.deepEqual(
    resolveSynapseReleaseVersion({
      workflowRunNumber: "19",
      currentReleaseBody: "Version code: 2020. Package: app.synapse.localllm.debug.",
    }),
    {versionCode: 2021, versionName: "0.1.2021"},
  );
});

test("keeps a newer workflow sequence monotonic", () => {
  assert.deepEqual(
    resolveSynapseReleaseVersion({
      workflowRunNumber: 25,
      currentReleaseBody: "Version code: 2020.",
    }),
    {versionCode: 2025, versionName: "0.1.2025"},
  );
});

test("fails closed when an existing release has no version receipt", () => {
  assert.throws(
    () => resolveSynapseReleaseVersion({workflowRunNumber: 19, currentReleaseBody: "Missing receipt"}),
    /does not contain a valid Version code receipt/,
  );
});

test("rejects invalid workflow run numbers", () => {
  assert.throws(
    () => resolveSynapseReleaseVersion({workflowRunNumber: "0", currentReleaseBody: null}),
    /workflow run number must be a positive integer/,
  );
});
