import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  collection,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
} from "firebase/firestore";

const PROJECT_ID = "demo-synapse-chat";
const PETER_UID = "peter-uid";
const TRISH_UID = "trish-uid";
const MALLORY_UID = "mallory-uid";
const ROOM_ID = `direct_${"a".repeat(64)}`;
const firestoreRules = fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8");

let testEnvironment;

function profile(uid, username, allowed = true) {
  const normalized = username.toLowerCase();
  return {
    allowed,
    avatarUrl: null,
    bio: "",
    createdAt: Timestamp.fromMillis(1),
    directoryVisible: true,
    displayName: username,
    lastSeenAt: null,
    online: false,
    updatedAt: Timestamp.fromMillis(1),
    username,
    usernameNormalized: normalized,
  };
}

async function seedChat() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    await setDoc(doc(firestore, "profiles", PETER_UID), profile(PETER_UID, "Peter"));
    await setDoc(doc(firestore, "profiles", TRISH_UID), profile(TRISH_UID, "Trish"));
    await setDoc(doc(firestore, "profiles", MALLORY_UID), profile(MALLORY_UID, "Mallory", false));
    await setDoc(doc(firestore, "rooms", ROOM_ID), {
      createdAt: Timestamp.fromMillis(1),
      directKey: `${PETER_UID}:${TRISH_UID}`,
      kind: "DIRECT",
      latestMessage: null,
      memberIds: [PETER_UID, TRISH_UID],
      title: "Peter, Trish",
      updatedAt: Timestamp.fromMillis(1),
    });
    for (const uid of [PETER_UID, TRISH_UID]) {
      await setDoc(doc(firestore, "rooms", ROOM_ID, "members", uid), {
        active: true,
        joinedAt: Timestamp.fromMillis(1),
        lastReadAt: null,
        role: "MEMBER",
        uid,
        unreadCount: 0,
      });
    }
  });
}

function validMessage(senderUid, messageId, body = "Hello") {
  return {
    authorKind: "HUMAN",
    body,
    clientCreatedAt: Timestamp.now(),
    clientMessageId: messageId,
    createdAt: serverTimestamp(),
    deletedAt: null,
    editedAt: null,
    replyToMessageId: null,
    senderUid,
  };
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: firestoreRules},
    projectId: PROJECT_ID,
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
  await seedChat();
});

after(async () => {
  await testEnvironment.cleanup();
});

test("requires authentication and allowlist membership for the directory", async () => {
  const unauthenticated = testEnvironment.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(unauthenticated, "profiles", PETER_UID)));

  const peter = testEnvironment.authenticatedContext(PETER_UID).firestore();
  const directoryQuery = query(
    collection(peter, "profiles"),
    where("allowed", "==", true),
    orderBy("usernameNormalized"),
  );
  const directory = await assertSucceeds(getDocs(directoryQuery));
  const usernames = directory.docs.map((snapshot) => snapshot.get("username"));
  usernames.sort();
  if (usernames.join(",") !== "Peter,Trish") {
    throw new Error(`Unexpected directory contents: ${usernames.join(",")}`);
  }
});

test("permits presentation-only self profile updates and rejects identity changes", async () => {
  const peter = testEnvironment.authenticatedContext(PETER_UID).firestore();
  const peterProfile = doc(peter, "profiles", PETER_UID);
  await assertSucceeds(
    updateDoc(peterProfile, {
      bio: "Local-first builder",
      displayName: "Peter R.",
      lastSeenAt: serverTimestamp(),
      online: true,
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(updateDoc(peterProfile, {allowed: false}));
  await assertFails(updateDoc(peterProfile, {username: "Trish"}));

  const trish = testEnvironment.authenticatedContext(TRISH_UID).firestore();
  await assertFails(updateDoc(doc(trish, "profiles", PETER_UID), {displayName: "Forged"}));
});

test("denies room and message data to non-members", async () => {
  const mallory = testEnvironment.authenticatedContext(MALLORY_UID).firestore();
  await assertFails(getDoc(doc(mallory, "rooms", ROOM_ID)));
  await assertFails(getDocs(collection(mallory, "rooms", ROOM_ID, "messages")));

  const peter = testEnvironment.authenticatedContext(PETER_UID).firestore();
  await assertSucceeds(getDoc(doc(peter, "rooms", ROOM_ID)));
  await assertSucceeds(getDocs(collection(peter, "rooms", ROOM_ID, "messages")));
});

test("accepts idempotent member messages and rejects forged senders", async () => {
  const peter = testEnvironment.authenticatedContext(PETER_UID).firestore();
  const messageId = `${PETER_UID}_client-message-1`;
  await assertSucceeds(
    setDoc(doc(peter, "rooms", ROOM_ID, "messages", messageId), validMessage(PETER_UID, messageId)),
  );

  const forgedMessageId = `${PETER_UID}_forged-message`;
  await assertFails(
    setDoc(
      doc(peter, "rooms", ROOM_ID, "messages", forgedMessageId),
      validMessage(TRISH_UID, forgedMessageId),
    ),
  );
});

test("rejects invalid messages and all client membership writes", async () => {
  const peter = testEnvironment.authenticatedContext(PETER_UID).firestore();
  const blankMessageId = `${PETER_UID}_blank-message`;
  await assertFails(
    setDoc(doc(peter, "rooms", ROOM_ID, "messages", blankMessageId), validMessage(PETER_UID, blankMessageId, "")),
  );
  const longMessageId = `${PETER_UID}_long-message`;
  await assertFails(
    setDoc(
      doc(peter, "rooms", ROOM_ID, "messages", longMessageId),
      validMessage(PETER_UID, longMessageId, "x".repeat(4001)),
    ),
  );
  await assertFails(
    updateDoc(doc(peter, "rooms", ROOM_ID, "members", PETER_UID), {unreadCount: 0}),
  );
});

test("binds FCM installation IDs to the authenticated owner", async () => {
  const peter = testEnvironment.authenticatedContext(PETER_UID).firestore();
  const peterDevice = doc(peter, "devices", "peter-device");
  await assertSucceeds(
    setDoc(peterDevice, {
      active: true,
      createdAt: serverTimestamp(),
      installationId: "peter-firebase-installation-id",
      ownerUid: PETER_UID,
      platform: "ANDROID",
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(
    setDoc(doc(peter, "devices", "forged-device"), {
      active: true,
      createdAt: serverTimestamp(),
      installationId: "trish-firebase-installation-id",
      ownerUid: TRISH_UID,
      platform: "ANDROID",
      updatedAt: serverTimestamp(),
    }),
  );

  const trish = testEnvironment.authenticatedContext(TRISH_UID).firestore();
  await assertFails(getDoc(doc(trish, "devices", "peter-device")));
});
