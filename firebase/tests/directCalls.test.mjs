import assert from "node:assert/strict";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {initializeTestEnvironment} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {getFirestore as getAdminFirestore, Timestamp as AdminTimestamp} from "firebase-admin/firestore";
import {deleteApp, initializeApp} from "firebase/app";
import {connectAuthEmulator, getAuth, signInWithEmailAndPassword, signOut} from "firebase/auth";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";

const PROJECT_ID = "demo-synapse-chat";
const FUNCTIONS_REGION = "northamerica-northeast1";
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const PASSWORD = "direct call emulator password";

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
    {apiKey: "demo-api-key", appId: `demo-direct-call-${name}`, projectId: PROJECT_ID},
    `direct-call-${name}-client`,
  );
  const auth = getAuth(app);
  connectAuthEmulator(auth, AUTH_EMULATOR_URL, {disableWarnings: true});
  const functions = getFunctions(app, FUNCTIONS_REGION);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  return {app, auth, functions};
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

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8")},
    projectId: PROJECT_ID,
  });
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "direct-call-admin-tests");
  adminAuth = getAdminAuth(adminApp);
  adminFirestore = getAdminFirestore(adminApp);
  ["peter", "trish"].forEach((name) => clients.set(name, createClient(name)));
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

test("a direct call rings, answers, exchanges signaling, and clears both active pointers", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const room = await call(peterClient, "openDirectRoom")({targetUid: trish.uid});

  const started = await call(peterClient, "startDirectCall")({mediaKind: "VIDEO", roomId: room.data.roomId});
  assert.equal(started.data.state, "RINGING");
  assert.equal(started.data.callerUid, peter.uid);
  assert.equal(started.data.calleeUid, trish.uid);
  assert.equal(started.data.mediaKind, "VIDEO");
  assert.match(started.data.callId, /^call_[a-f0-9]{32}$/);
  const callId = started.data.callId;
  assert.equal((await adminFirestore.doc(`callSessions/${callId}`).get()).get("mediaKind"), "VIDEO");

  const peterPointer = await adminFirestore.doc(`activeCallPointers/${peter.uid}`).get();
  const trishPointer = await adminFirestore.doc(`activeCallPointers/${trish.uid}`).get();
  assert.equal(peterPointer.get("callId"), callId);
  assert.equal(peterPointer.get("role"), "CALLER");
  assert.equal(trishPointer.get("callId"), callId);
  assert.equal(trishPointer.get("role"), "CALLEE");

  await assert.rejects(
    call(peterClient, "respondDirectCall")({action: "ACCEPT", callId}),
    (error) => error.code === "functions/permission-denied",
  );
  await assert.rejects(
    call(peterClient, "startDirectCall")({roomId: room.data.roomId}),
    (error) => error.code === "functions/failed-precondition",
  );
  await assert.rejects(
    call(peterClient, "publishDirectCallSignal")({
      callId,
      kind: "OFFER",
      sdp: "offer-before-accept",
      signalId: `signal_${"a".repeat(32)}`,
    }),
    (error) => error.code === "functions/failed-precondition",
  );

  const accepted = await call(trishClient, "respondDirectCall")({action: "ACCEPT", callId});
  assert.equal(accepted.data.state, "ACTIVE");
  assert.equal(accepted.data.mediaKind, "VIDEO");
  assert.equal(accepted.data.expiresAtMillis > started.data.expiresAtMillis, true);

  const offer = {
    callId,
    kind: "OFFER",
    sdp: "v=0\r\ns=Synapse offer",
    signalId: `signal_${"b".repeat(32)}`,
  };
  await call(peterClient, "publishDirectCallSignal")(offer);
  await call(peterClient, "publishDirectCallSignal")(offer);
  await assert.rejects(
    call(trishClient, "publishDirectCallSignal")({...offer, kind: "ANSWER", sdp: "conflicting answer"}),
    (error) => error.code === "functions/already-exists",
  );
  await assert.rejects(
    call(trishClient, "publishDirectCallSignal")({
      ...offer,
      signalId: `signal_${"c".repeat(32)}`,
    }),
    (error) => error.code === "functions/permission-denied",
  );
  await call(trishClient, "publishDirectCallSignal")({
    callId,
    kind: "ANSWER",
    sdp: "v=0\r\ns=Synapse answer",
    signalId: `signal_${"d".repeat(32)}`,
  });
  await call(peterClient, "publishDirectCallSignal")({
    callId,
    candidate: "candidate:1 1 udp 2122260223 192.0.2.1 54400 typ host",
    kind: "ICE",
    sdpMid: "0",
    sdpMLineIndex: 0,
    signalId: `signal_${"e".repeat(32)}`,
  });
  const signals = await adminFirestore.collection(`callSessions/${callId}/signals`).get();
  assert.equal(signals.size, 3);

  const ended = await call(trishClient, "endDirectCall")({callId});
  assert.equal(ended.data.state, "ENDED");
  assert.equal(ended.data.mediaKind, "VIDEO");
  assert.equal((await adminFirestore.doc(`activeCallPointers/${peter.uid}`).get()).exists, false);
  assert.equal((await adminFirestore.doc(`activeCallPointers/${trish.uid}`).get()).exists, false);
  assert.equal((await adminFirestore.doc(`callSessions/${callId}`).get()).get("endedByUid"), trish.uid);
  await assert.rejects(
    call(peterClient, "publishDirectCallSignal")({
      ...offer,
      signalId: `signal_${"f".repeat(32)}`,
    }),
    (error) => error.code === "functions/failed-precondition",
  );
  assert.equal((await call(peterClient, "endDirectCall")({callId})).data.state, "ENDED");
});

test("blocked pairs and disabled callees cannot receive direct calls", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const room = await call(peterClient, "openDirectRoom")({targetUid: trish.uid});

  await call(peterClient, "setUserBlocked")({blocked: true, targetUid: trish.uid});
  await assert.rejects(
    call(peterClient, "startDirectCall")({roomId: room.data.roomId}),
    (error) => error.code === "functions/permission-denied",
  );
  await call(peterClient, "setUserBlocked")({blocked: false, targetUid: trish.uid});
  await adminFirestore.doc(`profiles/${trish.uid}`).update({accountState: "DISABLED", allowed: false});
  await assert.rejects(
    call(peterClient, "startDirectCall")({roomId: room.data.roomId}),
    (error) => error.code === "functions/permission-denied",
  );
});
