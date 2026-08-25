import { assertEquals, assertRejects, assertThrows } from "@std/assert";
import { parseLeasedAttachmentBatch, removeLeasedAttachmentObjects } from "../_shared/retention.ts";

const LEASE = "018f1d9e-7b2a-7000-8000-000000000001";
const ROOM = "018f1d9e-7b2a-7000-8000-000000000002";
const MESSAGE = "018f1d9e-7b2a-7000-8000-000000000003";
const ATTACHMENT = "018f1d9e-7b2a-7000-8000-000000000004";

Deno.test("validates canonical DB-owned attachment paths", () => {
  const batch = parseLeasedAttachmentBatch([{
    lease_id: LEASE,
    message_id: MESSAGE,
    object_paths: [
      `${ROOM}/${MESSAGE}/${ATTACHMENT}.ciphertext`,
      `${ROOM}/${MESSAGE}/${ATTACHMENT}.thumbnail.ciphertext`,
    ],
  }]);
  assertEquals(batch?.messageCount, 1);
  assertEquals(batch?.objectPaths.length, 2);
});

Deno.test("rejects duplicate or noncanonical Storage paths", () => {
  const path = `${ROOM}/${MESSAGE}/${ATTACHMENT}.ciphertext`;
  assertThrows(() =>
    parseLeasedAttachmentBatch([{
      lease_id: LEASE,
      object_paths: [path, path],
    }])
  );
  assertThrows(() =>
    parseLeasedAttachmentBatch([{
      lease_id: LEASE,
      object_paths: ["../escape"],
    }])
  );
});

Deno.test("does not finalize after any Storage removal batch fails", async () => {
  const paths = Array.from(
    { length: 101 },
    (_, index) =>
      `${ROOM}/${MESSAGE}/${
        index.toString().padStart(8, "0")
      }-0000-4000-8000-000000000000.ciphertext`,
  );
  const batch = { leaseId: LEASE, messageCount: 1, objectPaths: paths };
  let calls = 0;
  await assertRejects(() =>
    removeLeasedAttachmentObjects(batch, () => {
      calls += 1;
      return calls === 2 ? Promise.reject(new Error("Storage unavailable")) : Promise.resolve();
    })
  );
  assertEquals(calls, 2);
});

Deno.test("treats an already-absent Storage object as an idempotent removal", async () => {
  const batch = {
    leaseId: LEASE,
    messageCount: 1,
    objectPaths: [`${ROOM}/${MESSAGE}/${ATTACHMENT}.ciphertext`],
  };
  let attemptedPaths: readonly string[] = [];
  await removeLeasedAttachmentObjects(batch, (paths) => {
    attemptedPaths = paths;
    return Promise.resolve();
  });
  assertEquals(attemptedPaths, batch.objectPaths);
});
