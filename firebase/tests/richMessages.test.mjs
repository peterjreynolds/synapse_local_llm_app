import assert from "node:assert/strict";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {assertFails, assertSucceeds, initializeTestEnvironment} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {getFirestore as getAdminFirestore, Timestamp as AdminTimestamp} from "firebase-admin/firestore";
import {deleteApp, initializeApp} from "firebase/app";
import {connectAuthEmulator, getAuth, signInWithEmailAndPassword, signOut} from "firebase/auth";
import {
  Timestamp,
  collection,
  connectFirestoreEmulator,
  doc,
  getDocs,
  getFirestore,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";

const PROJECT_ID = "demo-synapse-chat";
const FUNCTIONS_REGION = "northamerica-northeast1";
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const PASSWORD = "rich message test password";

let testEnvironment;
let adminApp;
let adminAuth;
let adminFirestore;
const clients = new Map();

async function clearAuthEmulator() {
  const response = await fetch(
    `${AUTH_EMULATOR_URL}/emulator/v1/projects/${PROJECT_ID}/accounts`,
    {method: "DELETE"},
  );
  assert.equal(response.ok, true, `Auth emulator cleanup failed with HTTP ${response.status}.`);
}

function createClient(name) {
  const app = initializeApp(
    {apiKey: "demo-api-key", appId: `demo-rich-${name}`, projectId: PROJECT_ID},
    `rich-${name}-client`,
  );
  const auth = getAuth(app);
  connectAuthEmulator(auth, AUTH_EMULATOR_URL, {disableWarnings: true});
  const firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, "127.0.0.1", 8080);
  const functions = getFunctions(app, FUNCTIONS_REGION);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  return {app, auth, firestore, functions};
}

async function seedActiveAccount(username, role = "USER") {
  const account = await adminAuth.createUser({
    email: `${username}@accounts.synapse.invalid`,
    emailVerified: true,
    password: PASSWORD,
  });
  await adminAuth.setCustomUserClaims(account.uid, {
    accountState: "ACTIVE",
    claimsVersion: 1,
    mustChangePassword: false,
    role,
  });
  await adminFirestore.doc(`profiles/${account.uid}`).create({
    accountState: "ACTIVE",
    allowed: true,
    avatarUrl: null,
    bio: "",
    createdAt: AdminTimestamp.now(),
    directoryVisible: true,
    displayName: username,
    lastSeenAt: null,
    mustChangePassword: false,
    online: false,
    role,
    updatedAt: AdminTimestamp.now(),
    username,
    usernameNormalized: username,
  });
  return account;
}

async function signIn(name, account) {
  const client = clients.get(name);
  await signInWithEmailAndPassword(client.auth, account.email, PASSWORD);
  return client;
}

function call(client, name) {
  return httpsCallable(client.functions, name);
}

async function waitForCondition(readState, condition, description) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const state = await readState();
    if (condition(state)) return state;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail(`Timed out waiting for ${description}.`);
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8")},
    projectId: PROJECT_ID,
  });
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "rich-message-admin-tests");
  adminAuth = getAdminAuth(adminApp);
  adminFirestore = getAdminFirestore(adminApp);
  ["peter", "trish", "josh"].forEach((name) => clients.set(name, createClient(name)));
});

beforeEach(async () => {
  for (const client of clients.values()) {
    if (client.auth.currentUser) await signOut(client.auth);
  }
  await clearAuthEmulator();
  await testEnvironment.clearFirestore();
});

after(async () => {
  for (const client of clients.values()) {
    if (client.auth.currentUser) await signOut(client.auth);
    await deleteApp(client.app);
  }
  await testEnvironment.cleanup();
  await deleteAdminApp(adminApp);
});

test("send, reply, edit, delete, reactions, and receipts are idempotent and authorized", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const josh = await seedActiveAccount("josh");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const joshClient = await signIn("josh", josh);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid, josh.uid],
    title: "Rich messages",
  });
  const roomId = created.data.roomId;
  const sentAt = Date.now();
  const sendCommand = {
    body: "Original message",
    clientCreatedAtMillis: sentAt,
    messageId: "message-rich-1",
    replyToMessageId: null,
    roomId,
  };
  const firstSend = await call(peterClient, "sendRemoteMessage")(sendCommand);
  const retriedSend = await call(peterClient, "sendRemoteMessage")(sendCommand);
  assert.equal(firstSend.data.revision, 1);
  assert.equal(retriedSend.data.revision, 1);
  await assert.rejects(
    call(peterClient, "sendRemoteMessage")({...sendCommand, body: "Conflicting retry"}),
    (error) => error.code === "functions/already-exists",
  );

  await call(trishClient, "sendRemoteMessage")({
    body: "Reply",
    clientCreatedAtMillis: sentAt + 1,
    messageId: "message-rich-reply",
    replyToMessageId: sendCommand.messageId,
    roomId,
  });
  await assert.rejects(
    call(trishClient, "editRemoteMessage")({
      body: "Forged edit",
      expectedRevision: 1,
      messageId: sendCommand.messageId,
      mutationId: "mutation-trish-forged-edit",
      roomId,
    }),
    (error) => error.code === "functions/permission-denied",
  );
  const editCommand = {
    body: "Edited message",
    expectedRevision: 1,
    messageId: sendCommand.messageId,
    mutationId: "mutation-peter-edit-0001",
    roomId,
  };
  const firstEdit = await call(peterClient, "editRemoteMessage")(editCommand);
  const retriedEdit = await call(peterClient, "editRemoteMessage")(editCommand);
  assert.equal(firstEdit.data.revision, 2);
  assert.equal(retriedEdit.data.revision, 2);

  const trishReaction = {
    emoji: "👍",
    messageId: sendCommand.messageId,
    reacted: true,
    roomId,
  };
  assert.equal((await call(trishClient, "toggleRemoteReaction")(trishReaction)).data.reactionCount, 1);
  assert.equal((await call(trishClient, "toggleRemoteReaction")(trishReaction)).data.reactionCount, 1);
  assert.equal((await call(joshClient, "toggleRemoteReaction")(trishReaction)).data.reactionCount, 2);
  assert.equal(
    (await call(trishClient, "toggleRemoteReaction")({...trishReaction, reacted: false})).data.reactionCount,
    1,
  );

  const acknowledgement = {
    messageIds: [sendCommand.messageId],
    read: false,
    roomId,
  };
  await call(trishClient, "acknowledgeRemoteMessages")(acknowledgement);
  await call(trishClient, "acknowledgeRemoteMessages")(acknowledgement);
  await call(trishClient, "acknowledgeRemoteMessages")({...acknowledgement, read: true});
  const acknowledgedMessage = await adminFirestore.doc(`rooms/${roomId}/messages/${sendCommand.messageId}`).get();
  assert.equal(acknowledgedMessage.get("deliveredToCount"), 1);
  assert.equal(acknowledgedMessage.get("readByCount"), 1);

  const deleteCommand = {
    expectedRevision: 2,
    messageId: sendCommand.messageId,
    mutationId: "mutation-peter-delete-01",
    roomId,
  };
  const firstDelete = await call(peterClient, "deleteRemoteMessage")(deleteCommand);
  const retriedDelete = await call(peterClient, "deleteRemoteMessage")(deleteCommand);
  assert.equal(firstDelete.data.revision, 3);
  assert.equal(retriedDelete.data.revision, 3);
  const deletedMessage = await adminFirestore.doc(`rooms/${roomId}/messages/${sendCommand.messageId}`).get();
  assert.equal(deletedMessage.get("body"), "");
  assert.equal(deletedMessage.get("deletedAt") instanceof AdminTimestamp, true);
  assert.deepEqual(deletedMessage.get("reactionCounts"), {});
  await assert.rejects(
    call(trishClient, "sendRemoteMessage")({
      body: "Cannot reply to a tombstone",
      clientCreatedAtMillis: sentAt + 2,
      messageId: "message-invalid-reply",
      replyToMessageId: sendCommand.messageId,
      roomId,
    }),
    (error) => error.code === "functions/failed-precondition",
  );
});

test("membership removal serializes against send and revokes typing access", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const josh = await seedActiveAccount("josh");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const joshClient = await signIn("josh", josh);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid, josh.uid],
    title: "Removal race",
  });
  const roomId = created.data.roomId;
  const joshTypingReference = doc(joshClient.firestore, "rooms", roomId, "typing", josh.uid);
  await assertSucceeds(
    setDoc(joshTypingReference, {
      expiresAt: Timestamp.fromMillis(Date.now() + 10_000),
      uid: josh.uid,
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(
    setDoc(doc(joshClient.firestore, "rooms", roomId, "typing", trish.uid), {
      expiresAt: Timestamp.fromMillis(Date.now() + 10_000),
      uid: trish.uid,
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(
    setDoc(joshTypingReference, {
      expiresAt: Timestamp.fromMillis(Date.now() + 60_000),
      uid: josh.uid,
      updatedAt: serverTimestamp(),
    }),
  );

  const raceResults = await Promise.allSettled([
    call(peterClient, "removeGroupMember")({roomId, targetUid: josh.uid}),
    call(joshClient, "sendRemoteMessage")({
      body: "Serialized with removal",
      clientCreatedAtMillis: Date.now(),
      messageId: "message-removal-race",
      replyToMessageId: null,
      roomId,
    }),
  ]);
  assert.equal(raceResults[0].status, "fulfilled");
  await assert.rejects(
    call(joshClient, "sendRemoteMessage")({
      body: "Denied after removal",
      clientCreatedAtMillis: Date.now(),
      messageId: "message-after-removal",
      replyToMessageId: null,
      roomId,
    }),
    (error) => error.code === "functions/permission-denied",
  );
  await assertFails(getDocs(collection(joshClient.firestore, "rooms", roomId, "typing")));
  const membership = await adminFirestore.doc(`rooms/${roomId}/members/${josh.uid}`).get();
  assert.equal(membership.get("active"), false);
  const racedSend = raceResults[1];
  if (racedSend.status === "fulfilled") {
    const message = await adminFirestore.doc(`rooms/${roomId}/messages/message-removal-race`).get();
    assert.equal(message.exists, true);
    assert.equal(message.get("senderUid"), josh.uid);
  } else {
    assert.equal(racedSend.reason.code, "functions/permission-denied");
  }
});

test("muted recipients retain zero unread while latest-message revisions stay current", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Muted summary",
  });
  const roomId = created.data.roomId;
  await call(trishClient, "updateGroupPreferences")({
    archived: false,
    muted: true,
    pinned: false,
    roomId,
  });
  await call(peterClient, "sendRemoteMessage")({
    body: "Initial latest body",
    clientCreatedAtMillis: Date.now(),
    messageId: "message-muted-summary",
    replyToMessageId: null,
    roomId,
  });

  await waitForCondition(
    () => adminFirestore.doc(`rooms/${roomId}`).get(),
    (snapshot) => snapshot.get("latestMessage.messageId") === "message-muted-summary",
    "the muted-room message summary",
  );
  const membership = await adminFirestore.doc(`rooms/${roomId}/members/${trish.uid}`).get();
  assert.equal(membership.get("unreadCount"), 0);

  await call(peterClient, "editRemoteMessage")({
    body: "Edited latest body",
    expectedRevision: 1,
    messageId: "message-muted-summary",
    mutationId: "mutation-muted-edit-01",
    roomId,
  });
  assert.equal(
    (await adminFirestore.doc(`rooms/${roomId}`).get()).get("latestMessage.body"),
    "Edited latest body",
  );
  await call(peterClient, "deleteRemoteMessage")({
    expectedRevision: 2,
    messageId: "message-muted-summary",
    mutationId: "mutation-muted-delete-01",
    roomId,
  });
  assert.equal(
    (await adminFirestore.doc(`rooms/${roomId}`).get()).get("latestMessage.body"),
    "Message deleted",
  );
});
