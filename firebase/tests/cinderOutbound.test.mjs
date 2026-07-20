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
const WORKER_ENDPOINT =
  `http://127.0.0.1:5001/${PROJECT_ID}/${FUNCTIONS_REGION}/sendCinderOutboundMessage`;
const CLAIM_ENDPOINT =
  `http://127.0.0.1:5001/${PROJECT_ID}/${FUNCTIONS_REGION}/claimCinderResponse`;
const SKIP_ENDPOINT =
  `http://127.0.0.1:5001/${PROJECT_ID}/${FUNCTIONS_REGION}/skipCinderResponseJob`;
const WORKER_TOKEN = "cinder-outbound-emulator-worker-token-1234567890";
const WORKER_ID = "openclaw-cinder-emulator";
const PASSWORD = "cinder outbound emulator password";

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
    {apiKey: "demo-api-key", appId: `demo-cinder-outbound-${name}`, projectId: PROJECT_ID},
    `cinder-outbound-${name}-client`,
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

async function sendOutbound(command, token = WORKER_TOKEN) {
  return workerRequest(WORKER_ENDPOINT, {workerId: WORKER_ID, ...command}, token);
}

async function workerRequest(endpoint, body, token = WORKER_TOKEN) {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
  return {body: await response.json(), status: response.status};
}

async function waitForCondition(condition, timeoutMillis = 30_000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (await condition()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail("Timed out waiting for Cinder outbound side effects.");
}

async function participantAuditCount(roomId) {
  return (await adminFirestore.collection("cinderAuditEvents").where("roomId", "==", roomId).get()).size;
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8")},
    projectId: PROJECT_ID,
  });
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "cinder-outbound-admin-tests");
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

test("dedicated proactive Cinder delivery is authenticated, idempotent, synchronized, and notified", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const peterClient = await signIn("peter", peter);
  const command = {
    accountUid: peter.uid,
    body: "A proactive Cinder follow-up.",
    idempotencyKey: "a".repeat(64),
    roomId: "assistant_cinder",
  };

  const unauthorized = await sendOutbound(command, "wrong-worker-token-that-is-still-long-enough");
  assert.deepEqual(unauthorized, {body: {error: "UNAUTHORIZED"}, status: 401});

  const first = await sendOutbound(command);
  const repeated = await sendOutbound(command);
  assert.equal(first.status, 200);
  assert.deepEqual(repeated, first);
  assert.deepEqual(first.body, {
    protocolVersion: 1,
    result: {
      messageId: `cinder-${"a".repeat(64)}`,
      roomId: "assistant_cinder",
      sequence: 1,
    },
  });

  const conflicting = await sendOutbound({...command, body: "Conflicting content."});
  assert.deepEqual(conflicting, {body: {error: "IDEMPOTENCY_CONFLICT"}, status: 409});
  const messages = await adminFirestore.collection(`cinderConversations/${peter.uid}/messages`).get();
  assert.equal(messages.size, 1);
  assert.equal(messages.docs[0].get("authorKind"), "REMOTE_AI");
  assert.equal(messages.docs[0].get("aiProvider"), "OPENCLAW_CINDER");

  const sync = await call(peterClient, "syncCinderMessages")({afterSequence: 0, limit: 10});
  assert.equal(sync.data.messages.length, 1);
  assert.equal(sync.data.messages[0].messageId, first.body.result.messageId);
  assert.equal(sync.data.messages[0].body, command.body);
  await waitForCondition(async () => (
    await adminFirestore.collection("notificationDeliveries")
      .where("messageId", "==", first.body.result.messageId)
      .get()
  ).size === 1);
});

test("human-room outbound delivery follows one room-scoped participant revision and removal gate", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Cinder continuity",
  });
  const roomId = created.data.roomId;

  const added = await call(peterClient, "setCinderParticipant")({
    active: true,
    expectedRevision: 0,
    mode: "AUTO",
    roomId,
  });
  const duplicateAdd = await call(peterClient, "setCinderParticipant")({
    active: true,
    expectedRevision: 0,
    mode: "AUTO",
    roomId,
  });
  assert.equal(added.data.revision, 1);
  assert.equal(added.data.mode, "AUTO");
  assert.deepEqual(duplicateAdd.data, added.data);
  assert.equal(await participantAuditCount(roomId), 1);

  const first = await sendOutbound({
    accountUid: peter.uid,
    body: "Room-scoped proactive context.",
    idempotencyKey: "b".repeat(64),
    roomId,
  });
  assert.equal(first.status, 200);
  assert.equal(first.body.result.roomId, roomId);
  const message = await adminFirestore.doc(`rooms/${roomId}/messages/${first.body.result.messageId}`).get();
  assert.equal(message.get("senderUid"), "participant-cinder-remote-ai");
  assert.equal(message.get("sourceMessageId"), null);
  await waitForCondition(async () => (
    await adminFirestore.doc(`rooms/${roomId}/members/${trish.uid}`).get()
  ).get("unreadCount") === 1);

  const trishClient = await signIn("trish", trish);
  const sharedState = await call(trishClient, "getCinderParticipant")({roomId});
  assert.equal(sharedState.data.active, true);
  assert.equal(sharedState.data.mode, "AUTO");
  assert.equal(sharedState.data.revision, 1);
  assert.equal(sharedState.data.canManage, false);

  const mentionMode = await call(peterClient, "setCinderParticipant")({
    active: true,
    expectedRevision: 1,
    mode: "MENTION",
    roomId,
  });
  assert.equal(mentionMode.data.revision, 2);
  const mentionRejected = await sendOutbound({
    accountUid: peter.uid,
    body: "Mention-only rooms reject proactive delivery.",
    idempotencyKey: "c".repeat(64),
    roomId,
  });
  assert.deepEqual(mentionRejected, {body: {error: "TARGET_UNAVAILABLE"}, status: 404});
  await assert.rejects(
    call(peterClient, "setCinderParticipant")({
      active: true,
      expectedRevision: 1,
      mode: "SILENT",
      roomId,
    }),
    (error) => error.code === "functions/aborted",
  );
  const removed = await call(peterClient, "setCinderParticipant")({
    active: false,
    expectedRevision: 2,
    mode: "MENTION",
    roomId,
  });
  const duplicateRemove = await call(peterClient, "setCinderParticipant")({
    active: false,
    expectedRevision: 2,
    mode: "MENTION",
    roomId,
  });
  assert.equal(removed.data.revision, 3);
  assert.deepEqual(duplicateRemove.data, removed.data);
  assert.equal(await participantAuditCount(roomId), 3);
  assert.deepEqual(
    await sendOutbound({
      accountUid: peter.uid,
      body: "Room-scoped proactive context.",
      idempotencyKey: "b".repeat(64),
      roomId,
    }),
    first,
  );
  const rejected = await sendOutbound({
    accountUid: peter.uid,
    body: "This must not be delivered.",
    idempotencyKey: "d".repeat(64),
    roomId,
  });
  assert.deepEqual(rejected, {body: {error: "TARGET_UNAVAILABLE"}, status: 404});
  assert.equal(
    (await adminFirestore.doc(`rooms/${roomId}/messages/cinder-${"d".repeat(64)}`).get()).exists,
    false,
  );
});

test("Silent, Mention, and Auto queue only eligible work and capability-gate automatic claims", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Cinder modes",
  });
  const roomId = created.data.roomId;
  const silent = await call(peterClient, "setCinderParticipant")({
    active: true,
    expectedRevision: 0,
    mode: "SILENT",
    roomId,
  });
  assert.equal(silent.data.mode, "SILENT");

  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "@Cinder this room is intentionally quiet.",
    clientCreatedAtMillis: Date.now(),
    messageId: "silent-source",
    replyToMessageId: null,
    roomId,
  });
  await new Promise((resolve) => setTimeout(resolve, 750));
  assert.equal(
    (await adminFirestore.collection("cinderResponseJobs").where("roomId", "==", roomId).get()).size,
    0,
  );

  const mention = await call(peterClient, "setCinderParticipant")({
    active: true,
    expectedRevision: silent.data.revision,
    mode: "MENTION",
    roomId,
  });
  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "Ordinary room context does not summon Cinder.",
    clientCreatedAtMillis: Date.now(),
    messageId: "mention-ineligible-source",
    replyToMessageId: null,
    roomId,
  });
  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "@Cinder summarize the current room context.",
    clientCreatedAtMillis: Date.now(),
    messageId: "mention-source",
    replyToMessageId: null,
    roomId,
  });
  await waitForCondition(async () => (
    await adminFirestore.collection("cinderResponseJobs").where("roomId", "==", roomId).get()
  ).size === 1);
  const mentionClaim = await workerRequest(CLAIM_ENDPOINT, {workerId: `${WORKER_ID}-legacy`});
  assert.equal(mentionClaim.status, 200);
  assert.equal(mentionClaim.body.result.claim.explicitMention, true);
  assert.equal(mentionClaim.body.result.claim.responsePolicy, "MENTION_ONLY");
  await workerRequest(SKIP_ENDPOINT, {
    jobId: mentionClaim.body.result.claim.jobId,
    leaseId: mentionClaim.body.result.claim.leaseId,
    leaseToken: mentionClaim.body.result.claim.leaseToken,
    reason: "DISPATCH_SKIPPED",
    workerId: `${WORKER_ID}-legacy`,
  });

  await call(peterClient, "setCinderParticipant")({
    active: true,
    expectedRevision: mention.data.revision,
    mode: "AUTO",
    roomId,
  });
  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "Automatic mode can respond without a mention.",
    clientCreatedAtMillis: Date.now(),
    messageId: "auto-source",
    replyToMessageId: null,
    roomId,
  });
  await waitForCondition(async () => (
    await adminFirestore.collection("cinderResponseJobs").where("roomId", "==", roomId).get()
  ).size === 1);
  const legacyClaim = await workerRequest(CLAIM_ENDPOINT, {workerId: `${WORKER_ID}-legacy`});
  assert.equal(legacyClaim.body.result.claim, null);
  const automaticClaim = await workerRequest(CLAIM_ENDPOINT, {
    supportedResponsePolicies: ["MENTION_ONLY", "AUTOMATIC"],
    workerId: `${WORKER_ID}-modern`,
  });
  assert.equal(automaticClaim.status, 200);
  assert.equal(automaticClaim.body.result.claim.explicitMention, false);
  assert.equal(automaticClaim.body.result.claim.responsePolicy, "AUTOMATIC");
});
