import assert from "node:assert/strict";
import test from "node:test";
import {Timestamp} from "firebase-admin/firestore";
import {buildAccountProfileDocument} from "./accountCreation.js";

test("builds one explicit server-owned account profile contract", () => {
  const createdAt = Timestamp.fromMillis(1_000);
  assert.deepEqual(
    buildAccountProfileDocument(
      {displayName: "Josh R.", usernameNormalized: "josh"},
      "USER",
      "PENDING_APPROVAL",
      createdAt,
    ),
    {
      accountState: "PENDING_APPROVAL",
      allowed: false,
      avatarUrl: null,
      bio: "",
      createdAt,
      directoryVisible: false,
      displayName: "Josh R.",
      lastSeenAt: null,
      mustChangePassword: false,
      online: false,
      role: "USER",
      updatedAt: createdAt,
      username: "josh",
      usernameNormalized: "josh",
    },
  );
});
