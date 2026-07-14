import assert from "node:assert/strict";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {assertFails, initializeTestEnvironment} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {getFirestore as getAdminFirestore, Timestamp as AdminTimestamp} from "firebase-admin/firestore";
import {deleteApp, initializeApp} from "firebase/app";
import {connectAuthEmulator, getAuth, signInWithEmailAndPassword, signOut} from "firebase/auth";
import {
  Timestamp,
  connectFirestoreEmulator,
  doc,
  getFirestore,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";
import {buildUserBlockDocumentId} from "../functions/lib/privacyAdmin.js";

const PROJECT_ID = "demo-synapse-chat";
const FUNCTIONS_REGION = "northamerica-northeast1";
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const PASSWORD = "group room test password";

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
    {apiKey: "demo-api-key", appId: `demo-group-${name}`, projectId: PROJECT_ID},
    `group-${name}-client`,
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

async function waitForCondition(condition, timeoutMillis = 10_000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (await condition()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail("Timed out waiting for the group notification authorization receipt.");
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8")},
    projectId: PROJECT_ID,
  });
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "group-room-admin-tests");
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

test("group roles, concurrent adds, ownership transfer, leave, and removal are server authoritative", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const josh = await seedActiveAccount("josh");
  const peterClient = await signIn("peter", peter);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Family",
  });
  const roomId = created.data.roomId;
  assert.match(roomId, /^group_[a-f0-9]{32}$/);

  const concurrentAdds = await Promise.all([
    call(peterClient, "addGroupMembers")({memberUids: [josh.uid], roomId}),
    call(peterClient, "addGroupMembers")({memberUids: [josh.uid], roomId}),
  ]);
  assert.equal(concurrentAdds.every((receipt) => receipt.data.activeMemberCount === 3), true);

  await call(peterClient, "setGroupMemberRole")({role: "ADMIN", roomId, targetUid: trish.uid});
  await call(peterClient, "transferGroupOwnership")({roomId, targetUid: trish.uid});
  await call(peterClient, "leaveGroupRoom")({roomId});

  const trishClient = await signIn("trish", trish);
  const details = await call(trishClient, "getGroupRoomDetails")({roomId});
  assert.equal(details.data.ownerUid, trish.uid);
  assert.deepEqual(
    details.data.members.map((member) => member.uid).sort(),
    [josh.uid, trish.uid].sort(),
  );
  await assert.rejects(
    call(trishClient, "leaveGroupRoom")({roomId}),
    (error) => error.code === "functions/failed-precondition",
  );
  await call(trishClient, "removeGroupMember")({roomId, targetUid: josh.uid});

  const joshClient = await signIn("josh", josh);
  await assertFails(
    setDoc(doc(joshClient.firestore, "rooms", roomId, "messages", "removed-message"), {
      authorKind: "HUMAN",
      body: "must not send after removal",
      clientCreatedAt: Timestamp.now(),
      clientMessageId: "removed-message",
      createdAt: serverTimestamp(),
      deletedAt: null,
      editedAt: null,
      replyToMessageId: null,
      senderUid: josh.uid,
    }),
  );
});

test("blocked pairs and disabled accounts cannot be added to groups", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const josh = await seedActiveAccount("josh");
  const peterClient = await signIn("peter", peter);
  const blockId = buildUserBlockDocumentId(peter.uid, trish.uid);
  await adminFirestore.doc(`blocks/${blockId}`).create({
    blockedUid: trish.uid,
    blockerUid: peter.uid,
    createdAt: AdminTimestamp.now(),
  });
  await assert.rejects(
    call(peterClient, "createGroupRoom")({memberUids: [trish.uid], title: "Blocked"}),
    (error) => error.code === "functions/permission-denied",
  );
  await adminFirestore.doc(`blocks/${blockId}`).delete();
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Allowed",
  });
  const trishClient = await signIn("trish", trish);
  await call(trishClient, "setUserBlocked")({blocked: true, targetUid: josh.uid});
  await assert.rejects(
    call(peterClient, "addGroupMembers")({memberUids: [josh.uid], roomId: created.data.roomId}),
    (error) => error.code === "functions/permission-denied",
  );
  await call(trishClient, "setUserBlocked")({blocked: false, targetUid: josh.uid});
  await adminFirestore.doc(`profiles/${josh.uid}`).update({accountState: "DISABLED", allowed: false});
  await assert.rejects(
    call(peterClient, "addGroupMembers")({memberUids: [josh.uid], roomId: created.data.roomId}),
    (error) => error.code === "functions/permission-denied",
  );
});

test("member preferences, role permissions, and deletion confirmation fail closed", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({memberUids: [trish.uid], title: "Project"});
  const roomId = created.data.roomId;
  await call(trishClient, "updateGroupPreferences")({archived: true, muted: true, pinned: false, roomId});
  const details = await call(trishClient, "getGroupRoomDetails")({roomId});
  assert.equal(details.data.archived, true);
  assert.equal(details.data.muted, true);
  await assert.rejects(
    call(trishClient, "renameGroupRoom")({roomId, title: "Unauthorized"}),
    (error) => error.code === "functions/permission-denied",
  );
  await assert.rejects(
    call(peterClient, "deleteGroupRoom")({confirmTitle: "wrong", roomId}),
    (error) => error.code === "functions/failed-precondition",
  );
  await call(peterClient, "deleteGroupRoom")({confirmTitle: "Project", roomId});
  await assert.rejects(
    call(trishClient, "getGroupRoomDetails")({roomId}),
    (error) => error.code === "functions/failed-precondition",
  );
});

test("group notification fan-out increments unread only for authorized unmuted members", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const josh = await seedActiveAccount("josh");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid, josh.uid],
    title: "Notifications",
  });
  const roomId = created.data.roomId;
  await call(trishClient, "updateGroupPreferences")({
    archived: false,
    muted: true,
    pinned: false,
    roomId,
  });
  await setDoc(doc(peterClient.firestore, "rooms", roomId, "messages", "notification-message"), {
    authorKind: "HUMAN",
    body: "Only unmuted members should receive this.",
    clientCreatedAt: Timestamp.now(),
    clientMessageId: "notification-message",
    createdAt: serverTimestamp(),
    deletedAt: null,
    editedAt: null,
    replyToMessageId: null,
    senderUid: peter.uid,
  });

  await waitForCondition(async () => {
    const joshMembership = await adminFirestore.doc(`rooms/${roomId}/members/${josh.uid}`).get();
    return joshMembership.get("unreadCount") === 1;
  });
  const trishMembership = await adminFirestore.doc(`rooms/${roomId}/members/${trish.uid}`).get();
  const joshMembership = await adminFirestore.doc(`rooms/${roomId}/members/${josh.uid}`).get();
  assert.equal(trishMembership.get("unreadCount"), 0);
  assert.equal(joshMembership.get("unreadCount"), 1);
});
