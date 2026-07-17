import assert from "node:assert/strict";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {initializeTestEnvironment} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {getFirestore as getAdminFirestore, Timestamp as AdminTimestamp} from "firebase-admin/firestore";
import {deleteApp, initializeApp} from "firebase/app";
import {connectAuthEmulator, getAuth, signInWithEmailAndPassword, signOut} from "firebase/auth";
import {connectFirestoreEmulator, getFirestore} from "firebase/firestore";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";
import {
  connectStorageEmulator,
  getBytes,
  getMetadata,
  getStorage,
  ref,
  uploadBytes,
} from "firebase/storage";

const PROJECT_ID = "demo-synapse-chat";
const BUCKET_NAME = `${PROJECT_ID}.appspot.com`;
const FUNCTIONS_REGION = "northamerica-northeast1";
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const PASSWORD = "attachment test password";
const ATTACHMENT_ID = "attachment-12345678-1234-4123-8123-123456789abc";

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
    {
      apiKey: "demo-api-key",
      appId: `demo-attachment-${name}`,
      projectId: PROJECT_ID,
      storageBucket: BUCKET_NAME,
    },
    `attachment-${name}-client`,
  );
  const auth = getAuth(app);
  connectAuthEmulator(auth, AUTH_EMULATOR_URL, {disableWarnings: true});
  const firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, "127.0.0.1", 8080);
  const functions = getFunctions(app, FUNCTIONS_REGION);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  const storage = getStorage(app);
  connectStorageEmulator(storage, "127.0.0.1", 9199);
  return {app, auth, firestore, functions, storage};
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

function uploadMetadata(ownerUid, roomId, messageId, variant) {
  return {
    attachmentId: ATTACHMENT_ID,
    messageId,
    ownerUid,
    roomId,
    variant,
  };
}

async function waitForCondition(readState, condition, description) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
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
    storage: {rules: fs.readFileSync(new URL("../storage.rules", import.meta.url), "utf8")},
  });
  adminApp = initializeAdminApp(
    {projectId: PROJECT_ID, storageBucket: BUCKET_NAME},
    "attachment-admin-tests",
  );
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
  await testEnvironment.clearStorage();
});

after(async () => {
  for (const client of clients.values()) {
    if (client.auth.currentUser) await signOut(client.auth);
    await deleteApp(client.app);
  }
  await testEnvironment.cleanup();
  await deleteAdminApp(adminApp);
});

test("prepare, upload, finalize, send, authorize download, and tombstone cleanup are idempotent", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Attachments",
  });
  const roomId = created.data.roomId;
  const messageId = "message-with-attachment";
  const prepareCommand = {
    attachmentId: ATTACHMENT_ID,
    byteCount: 3,
    displayName: "report.EXE",
    durationMillis: null,
    kind: "DOCUMENT",
    messageId,
    mimeType: "application/pdf",
    roomId,
  };
  const prepared = await call(peterClient, "prepareRemoteAttachment")(prepareCommand);
  const retriedPrepare = await call(peterClient, "prepareRemoteAttachment")(prepareCommand);
  assert.equal(prepared.data.contentObjectPath, retriedPrepare.data.contentObjectPath);
  assert.equal(prepared.data.status, "PENDING");
  await assert.rejects(
    call(peterClient, "prepareRemoteAttachment")({...prepareCommand, byteCount: 4}),
    (error) => error.code === "functions/already-exists",
  );

  const contentReference = ref(peterClient.storage, prepared.data.contentObjectPath);
  await uploadBytes(contentReference, new Uint8Array([1, 2, 3]), {
    contentType: "application/pdf",
    customMetadata: uploadMetadata(peter.uid, roomId, messageId, "content"),
  });
  assert.equal((await call(peterClient, "finalizeRemoteAttachment")({
    attachmentId: ATTACHMENT_ID,
    messageId,
    roomId,
  })).data.status, "READY");
  assert.equal((await call(peterClient, "finalizeRemoteAttachment")({
    attachmentId: ATTACHMENT_ID,
    messageId,
    roomId,
  })).data.status, "READY");

  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [ATTACHMENT_ID],
    body: "",
    clientCreatedAtMillis: Date.now(),
    messageId,
    replyToMessageId: null,
    roomId,
  });
  const sentMessage = await adminFirestore.doc(`rooms/${roomId}/messages/${messageId}`).get();
  const sentAttachments = sentMessage.get("attachments");
  assert.equal(sentAttachments[0].displayName, "report.pdf");
  assert.equal(sentAttachments[0].contentObjectPath, prepared.data.contentObjectPath);
  assert.deepEqual(
    Array.from(new Uint8Array(await getBytes(ref(trishClient.storage, prepared.data.contentObjectPath)))),
    [1, 2, 3],
  );

  await call(peterClient, "deleteRemoteMessage")({
    expectedRevision: 1,
    messageId,
    mutationId: "delete-attachment-message-0001",
    roomId,
  });
  await waitForCondition(
    () => adminFirestore.doc(`attachmentUploads/${ATTACHMENT_ID}`).get(),
    (snapshot) => snapshot.get("status") === "CLEANED",
    "attachment tombstone cleanup",
  );
  await assert.rejects(getMetadata(contentReference));
});

test("GIF images retain their MIME type and require a separate JPEG thumbnail", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "GIF attachments",
  });
  const roomId = created.data.roomId;
  const messageId = "message-with-gif";
  const gifBytes = new TextEncoder().encode("GIF89a");
  const prepared = await call(peterClient, "prepareRemoteAttachment")({
    attachmentId: ATTACHMENT_ID,
    byteCount: gifBytes.byteLength,
    displayName: "reaction.not-a-jpg",
    durationMillis: null,
    kind: "IMAGE",
    messageId,
    mimeType: "image/gif",
    roomId,
  });

  await uploadBytes(ref(peterClient.storage, prepared.data.contentObjectPath), gifBytes, {
    contentType: "image/gif",
    customMetadata: uploadMetadata(peter.uid, roomId, messageId, "content"),
  });
  await uploadBytes(ref(peterClient.storage, prepared.data.thumbnailObjectPath), new Uint8Array([1, 2, 3]), {
    contentType: "image/jpeg",
    customMetadata: uploadMetadata(peter.uid, roomId, messageId, "thumbnail"),
  });
  assert.equal((await call(peterClient, "finalizeRemoteAttachment")({
    attachmentId: ATTACHMENT_ID,
    messageId,
    roomId,
  })).data.status, "READY");

  await call(peterClient, "sendRemoteMessage")({
    attachmentIds: [ATTACHMENT_ID],
    body: "",
    clientCreatedAtMillis: Date.now(),
    messageId,
    replyToMessageId: null,
    roomId,
  });
  const sentMessage = await adminFirestore.doc(`rooms/${roomId}/messages/${messageId}`).get();
  const [sentAttachment] = sentMessage.get("attachments");
  assert.equal(sentAttachment.displayName, "reaction.gif");
  assert.equal(sentAttachment.mimeType, "image/gif");
  assert.equal(sentAttachment.kind, "IMAGE");
  await waitForCondition(
    () => adminFirestore.collection("notificationDeliveries").where("messageId", "==", messageId).get(),
    (snapshot) => snapshot.docs.some((document) => document.get("state") === "COMPLETE"),
    "GIF message notification receipt",
  );
});

test("failed metadata, cancellation, and deleted membership fail closed", async () => {
  const peter = await seedActiveAccount("peter", "OWNER");
  const trish = await seedActiveAccount("trish");
  const peterClient = await signIn("peter", peter);
  const trishClient = await signIn("trish", trish);
  const created = await call(peterClient, "createGroupRoom")({
    memberUids: [trish.uid],
    title: "Attachment failures",
  });
  const roomId = created.data.roomId;
  const messageId = "message-failed-attachment";
  const prepareCommand = {
    attachmentId: ATTACHMENT_ID,
    byteCount: 4,
    displayName: "notes.pdf",
    durationMillis: null,
    kind: "DOCUMENT",
    messageId,
    mimeType: "application/pdf",
    roomId,
  };
  const prepared = await call(peterClient, "prepareRemoteAttachment")(prepareCommand);
  await assert.rejects(uploadBytes(
    ref(peterClient.storage, prepared.data.contentObjectPath),
    new Uint8Array([1, 2, 3]),
    {
      contentType: "application/pdf",
      customMetadata: uploadMetadata(peter.uid, roomId, messageId, "content"),
    },
  ));
  await assert.rejects(
    call(peterClient, "finalizeRemoteAttachment")({attachmentId: ATTACHMENT_ID, messageId, roomId}),
    (error) => error.code === "functions/failed-precondition",
  );
  await call(peterClient, "cancelRemoteAttachment")({attachmentId: ATTACHMENT_ID, messageId, roomId});
  assert.equal(
    (await adminFirestore.doc(`attachmentUploads/${ATTACHMENT_ID}`).get()).get("status"),
    "CLEANED",
  );
  await call(peterClient, "removeGroupMember")({roomId, targetUid: trish.uid});
  await assert.rejects(
    call(trishClient, "prepareRemoteAttachment")({
      ...prepareCommand,
      attachmentId: "attachment-abcdefab-cdef-4abc-8def-abcdefabcdef",
    }),
    (error) => error.code === "functions/permission-denied",
  );
});
