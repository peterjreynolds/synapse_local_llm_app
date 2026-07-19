import assert from "node:assert/strict";
import test from "node:test";
import {HttpsError} from "firebase-functions/v2/https";
import {handleCinderWorkerBoundaryRequest} from "./cinderWorker.js";

test("worker boundary authenticates before parsing or mutating", async () => {
  let operationCalls = 0;
  const response = await handleCinderWorkerBoundaryRequest(
    {authorizationHeader: "Bearer wrong", body: {sensitive: "body"}, method: "POST"},
    () => false,
    async () => {
      operationCalls += 1;
      return {claim: null};
    },
  );

  assert.deepEqual(response, {body: {error: "UNAUTHORIZED"}, status: 401});
  assert.equal(operationCalls, 0);
});

test("worker boundary returns a versioned result without echoing authorization", async () => {
  const response = await handleCinderWorkerBoundaryRequest(
    {authorizationHeader: "Bearer secret", body: {workerId: "worker"}, method: "POST"},
    () => true,
    async (body) => ({accepted: body}),
  );

  assert.equal(response.status, 200);
  assert.deepEqual(response.body, {
    protocolVersion: 1,
    result: {accepted: {workerId: "worker"}},
  });
  assert.equal(JSON.stringify(response).includes("Bearer secret"), false);
});

test("worker boundary maps validation and lease conflicts without returning exception text", async () => {
  const invalid = await handleCinderWorkerBoundaryRequest(
    {authorizationHeader: "Bearer secret", body: {}, method: "POST"},
    () => true,
    async () => {
      throw new HttpsError("invalid-argument", "secret implementation detail");
    },
  );
  const conflict = await handleCinderWorkerBoundaryRequest(
    {authorizationHeader: "Bearer secret", body: {}, method: "POST"},
    () => true,
    async () => {
      throw new HttpsError("failed-precondition", "lease digest detail");
    },
  );

  assert.deepEqual(invalid, {body: {error: "INVALID_COMMAND"}, status: 400});
  assert.deepEqual(conflict, {body: {error: "LEASE_UNAVAILABLE"}, status: 409});
  assert.equal(JSON.stringify([invalid, conflict]).includes("detail"), false);
});
