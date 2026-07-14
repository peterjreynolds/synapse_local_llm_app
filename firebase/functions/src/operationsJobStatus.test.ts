import assert from "node:assert/strict";
import test from "node:test";
import {Timestamp} from "firebase-admin/firestore";
import {readOperationsJobStatus} from "./operationsJobStatus.js";

test("maps missing and valid durable cleanup receipts", () => {
  assert.equal(readOperationsJobStatus(undefined).state, "NEVER_RUN");
  const completedAt = Timestamp.fromMillis(2_000);
  assert.deepEqual(
    readOperationsJobStatus({
      affectedDocumentCount: 4,
      lastCompletedAt: completedAt,
      lastStartedAt: Timestamp.fromMillis(1_000),
      state: "SUCCEEDED",
    }),
    {
      affectedDocumentCount: 4,
      lastCompletedAtMillis: 2_000,
      lastStartedAtMillis: 1_000,
      state: "SUCCEEDED",
    },
  );
});

test("fails closed when a durable cleanup receipt is malformed", () => {
  assert.equal(readOperationsJobStatus({state: "COMPLETE"}).state, "FAILED");
  assert.equal(
    readOperationsJobStatus({affectedDocumentCount: -1, state: "SUCCEEDED"}).state,
    "FAILED",
  );
});
