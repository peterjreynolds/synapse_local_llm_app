import assert from "node:assert/strict";
import test from "node:test";
import {buildUserBlockDocumentId} from "./privacyAdmin.js";

test("builds directional opaque block identifiers", () => {
  const peterBlocksTrish = buildUserBlockDocumentId("peter-uid", "trish-uid");
  const trishBlocksPeter = buildUserBlockDocumentId("trish-uid", "peter-uid");
  assert.match(peterBlocksTrish, /^[a-f0-9]{64}$/);
  assert.notEqual(peterBlocksTrish, trishBlocksPeter);
  assert.equal(
    peterBlocksTrish,
    buildUserBlockDocumentId("peter-uid", "trish-uid"),
  );
  assert.throws(() => buildUserBlockDocumentId("peter-uid", "peter-uid"));
});
