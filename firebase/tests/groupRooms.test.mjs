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
import {buildRemoteAiJobId} from "../functions/lib/remoteAiDomain.js";

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

async function waitForCondition(condition, timeoutMillis = 30_000) {
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

test("room and notification preferences support direct rooms, groups, and timed mutes", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const group = await call(peterClient, "createGroupRoom")({memberUids: [trish.uid], title: "Preferences"});

  const timedMute = await call(trishClient, "updateRoomPreferences")({
    archived: true,
    muteDuration: "ONE_HOUR",
    pinned: true,
    roomId: group.data.roomId,
  });
  assert.equal(timedMute.data.muted, true);
  assert.equal(timedMute.data.mutedUntilMillis > Date.now(), true);
  const groupMembership = await adminFirestore.doc(`rooms/${group.data.roomId}/members/${trish.uid}`).get();
  assert.equal(groupMembership.get("archived"), true);
  assert.equal(groupMembership.get("pinned"), true);
  assert.equal(groupMembership.get("mutedUntil") instanceof AdminTimestamp, true);

  const direct = await call(peterClient, "openDirectRoom")({targetUid: trish.uid});
  const permanentMute = await call(trishClient, "updateRoomPreferences")({
    archived: false,
    muteDuration: "FOREVER",
    pinned: false,
    roomId: direct.data.roomId,
  });
  assert.equal(permanentMute.data.muted, true);
  assert.equal(permanentMute.data.mutedUntilMillis, null);

  const defaults = await call(trishClient, "getNotificationPreferences")();
  assert.deepEqual(defaults.data, {
    directMessages: true,
    groupMessages: true,
    mentions: true,
    mutedRooms: false,
  });
  const updated = await call(trishClient, "updateNotificationPreferences")({
    directMessages: false,
    groupMessages: false,
    mentions: true,
    mutedRooms: true,
  });
  assert.equal(updated.data.directMessages, false);
  assert.equal(updated.data.mutedRooms, true);
  await assertFails(
    setDoc(doc(trishClient.firestore, "notificationPreferences", trish.uid), {directMessages: true}),
  );
});

test("mention preferences can select a bounded subset of group recipients", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const josh = await seedActiveAccount("josh");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const joshClient = await signIn("josh", josh);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid, josh.uid],
    title: "Mentions",
  });
  await call(trishClient, "updateNotificationPreferences")({
    directMessages: true,
    groupMessages: false,
    mentions: true,
    mutedRooms: false,
  });
  await call(joshClient, "updateNotificationPreferences")({
    directMessages: true,
    groupMessages: false,
    mentions: false,
    mutedRooms: false,
  });
  await setDoc(doc(peterClient.firestore, "rooms", created.data.roomId, "messages", "mention-message"), {
    authorKind: "HUMAN",
    body: "@trish please review",
    clientCreatedAt: Timestamp.now(),
    clientMessageId: "mention-message",
    createdAt: serverTimestamp(),
    deletedAt: null,
    editedAt: null,
    replyToMessageId: null,
    senderUid: peter.uid,
  });

  await waitForCondition(async () => {
    const membership = await adminFirestore.doc(`rooms/${created.data.roomId}/members/${trish.uid}`).get();
    return membership.get("unreadCount") === 1;
  });
  const joshMembership = await adminFirestore.doc(`rooms/${created.data.roomId}/members/${josh.uid}`).get();
  assert.equal(joshMembership.get("unreadCount"), 0);
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

test("one designated host leases and posts one attributed local AI reply", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "AI lease",
  });
  const roomId = created.data.roomId;
  const device = await call(peterClient, "registerOwnDevice")({
    installationId: "ai-host-installation-00001",
  });
  const deviceId = device.data.deviceId;

  await assert.rejects(
    call(trishClient, "updateRoomAiConfiguration")({
      hostedAiEnabled: false,
      localAiAutoResponse: false,
      localAiEnabled: true,
      localAiHostDeviceId: deviceId,
      roomId,
    }),
    (error) => error.code === "functions/permission-denied",
  );
  await call(peterClient, "updateRoomAiConfiguration")({
    hostedAiEnabled: false,
    localAiAutoResponse: false,
    localAiEnabled: true,
    localAiHostDeviceId: deviceId,
    roomId,
  });
  const configuration = await call(peterClient, "getRoomAiConfiguration")({roomId});
  assert.equal(configuration.data.localAiEnabled, true);
  assert.equal(configuration.data.localAiHostAvailable, true);
  assert.equal(configuration.data.hostedAiStatus, "DISABLED_NO_PROVIDER");
  assert.equal(configuration.data.hostedExecutionPolicy.maximumMonthlyCostMicrousd, 0);
  const participant = await adminFirestore.doc(
    `rooms/${roomId}/participants/participant-synapse-local-ai`,
  ).get();
  assert.equal(participant.get("active"), true);
  assert.equal(participant.get("provenance"), "PHONE_LOCAL");
  const direct = await call(peterClient, "openDirectRoom")({targetUid: trish.uid});
  const directConfiguration = await call(peterClient, "updateRoomAiConfiguration")({
    hostedAiEnabled: false,
    localAiAutoResponse: true,
    localAiEnabled: true,
    localAiHostDeviceId: deviceId,
    roomId: direct.data.roomId,
  });
  assert.equal(directConfiguration.data.localAiEnabled, true);
  assert.equal(directConfiguration.data.localAiAutoResponse, true);
  await assert.rejects(
    call(peterClient, "updateRoomAiConfiguration")({
      hostedAiEnabled: true,
      localAiAutoResponse: false,
      localAiEnabled: true,
      localAiHostDeviceId: deviceId,
      roomId,
    }),
    (error) => error.code === "functions/failed-precondition",
  );

  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "Synapse should not answer this without a mention.",
    clientCreatedAtMillis: Date.now(),
    messageId: "human-only-source",
    replyToMessageId: null,
    roomId,
  });
  const skippedJobId = buildRemoteAiJobId(roomId, "human-only-source");
  await waitForCondition(async () => (
    await adminFirestore.doc(`localAiHostQueues/${deviceId}/jobs/${skippedJobId}`).get()
  ).exists);
  await assert.rejects(
    call(trishClient, "claimNextLocalAiResponse")({deviceId}),
    (error) => error.code === "functions/permission-denied",
  );
  const skippedClaim = (await call(peterClient, "claimNextLocalAiResponse")({deviceId})).data.claim;
  assert.equal(skippedClaim.jobId, skippedJobId);
  assert.equal(skippedClaim.responsePolicy, "MENTION_ONLY");
  await call(peterClient, "skipLocalAiResponse")({
    deviceId,
    jobId: skippedClaim.jobId,
    leaseToken: skippedClaim.leaseToken,
    reason: "MENTION_REQUIRED",
  });
  const skippedAudit = await adminFirestore.doc(`remoteAiResponseAudits/${skippedJobId}`).get();
  assert.equal(skippedAudit.get("completionState"), "SKIPPED");

  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "@Synapse exercise the bounded retry path.",
    clientCreatedAtMillis: Date.now(),
    messageId: "retry-source",
    replyToMessageId: null,
    roomId,
  });
  const exhaustedJobId = buildRemoteAiJobId(roomId, "retry-source");
  const exhaustedJobReference = adminFirestore.doc(
    `localAiHostQueues/${deviceId}/jobs/${exhaustedJobId}`,
  );
  await waitForCondition(async () => (await exhaustedJobReference.get()).exists);
  const firstRetryClaim = (await call(peterClient, "claimNextLocalAiResponse")({deviceId})).data.claim;
  assert.equal(firstRetryClaim.jobId, exhaustedJobId);
  const failedAttempt = await call(peterClient, "failLocalAiResponse")({
    deviceId,
    failureCode: "MODEL_UNAVAILABLE",
    jobId: exhaustedJobId,
    leaseToken: firstRetryClaim.leaseToken,
    retryable: true,
  });
  assert.equal(failedAttempt.data.retryScheduled, true);
  const secondRetryClaim = (await call(peterClient, "claimNextLocalAiResponse")({deviceId})).data.claim;
  assert.equal(secondRetryClaim.jobId, exhaustedJobId);
  await exhaustedJobReference.update({leaseExpiresAt: AdminTimestamp.fromMillis(Date.now() - 1)});
  assert.equal((await call(peterClient, "claimNextLocalAiResponse")({deviceId})).data.claim, null);
  assert.equal((await exhaustedJobReference.get()).exists, false);
  const exhaustedAudit = await adminFirestore.doc(`remoteAiResponseAudits/${exhaustedJobId}`).get();
  assert.equal(exhaustedAudit.get("completionState"), "FAILED");
  assert.equal(exhaustedAudit.get("failureCode"), "LEASE_ATTEMPTS_EXHAUSTED");

  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [],
    body: "@Synapse give us one answer.",
    clientCreatedAtMillis: Date.now(),
    messageId: "mentioned-source",
    replyToMessageId: null,
    roomId,
  });
  const responseJobId = buildRemoteAiJobId(roomId, "mentioned-source");
  await waitForCondition(async () => (
    await adminFirestore.doc(`localAiHostQueues/${deviceId}/jobs/${responseJobId}`).get()
  ).exists);
  const responseClaim = (await call(peterClient, "claimNextLocalAiResponse")({deviceId})).data.claim;
  assert.equal(responseClaim.jobId, responseJobId);
  assert.equal(responseClaim.sourceMessage.authorId, peter.uid);
  assert.equal(responseClaim.recentMessages.some((message) => message.body.includes("@Synapse")), true);
  assert.equal((await call(peterClient, "claimNextLocalAiResponse")({deviceId})).data.claim, null);

  const unreadBeforeAi = (await adminFirestore.doc(`rooms/${roomId}/members/${trish.uid}`).get())
    .get("unreadCount");
  await adminFirestore.doc(`devices/${deviceId}`).update({active: false});
  const completion = await call(peterClient, "completeLocalAiResponse")({
    body: "Exactly one phone-local answer.",
    deviceId,
    jobId: responseClaim.jobId,
    leaseToken: responseClaim.leaseToken,
  });
  const repeatedCompletion = await call(peterClient, "completeLocalAiResponse")({
    body: "Exactly one phone-local answer.",
    deviceId,
    jobId: responseClaim.jobId,
    leaseToken: responseClaim.leaseToken,
  });
  assert.deepEqual(repeatedCompletion.data, completion.data);
  await assert.rejects(
    call(peterClient, "completeLocalAiResponse")({
      body: "A conflicting replay must fail.",
      deviceId,
      jobId: responseClaim.jobId,
      leaseToken: responseClaim.leaseToken,
    }),
    (error) => error.code === "functions/already-exists",
  );
  const aiMessage = await adminFirestore.doc(`rooms/${roomId}/messages/${completion.data.messageId}`).get();
  assert.equal(aiMessage.get("authorKind"), "SYNAPSE_AI");
  assert.equal(aiMessage.get("senderUid"), "participant-synapse-local-ai");
  assert.equal(aiMessage.get("aiProvenance"), "PHONE_LOCAL");
  assert.equal(aiMessage.get("replyToMessageId"), "mentioned-source");
  await waitForCondition(async () => (
    await adminFirestore.doc(`rooms/${roomId}/members/${trish.uid}`).get()
  ).get("unreadCount") === unreadBeforeAi + 1);
  assert.equal((await adminFirestore.collection(`rooms/${roomId}/messages`)
    .where("aiResponseJobId", "==", responseJobId).get()).size, 1);
  await call(peterClient, "deleteRemoteMessage")({
    expectedRevision: 1,
    messageId: completion.data.messageId,
    mutationId: "delete-local-ai-response-0001",
    roomId,
  });
  const deletedAiMessage = await adminFirestore.doc(`rooms/${roomId}/messages/${completion.data.messageId}`).get();
  assert.equal(deletedAiMessage.get("body"), "");
  assert.equal(deletedAiMessage.get("deletedAt") instanceof AdminTimestamp, true);
});
