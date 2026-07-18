import assert from "node:assert/strict";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {initializeTestEnvironment} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {getFirestore as getAdminFirestore, Timestamp as AdminTimestamp} from "firebase-admin/firestore";
import {deleteApp, initializeApp} from "firebase/app";
import {
  connectAuthEmulator,
  getAuth,
  signInWithEmailAndPassword,
  signOut,
} from "firebase/auth";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";
import {requestAccountDeletion} from "../functions/lib/privacyAdmin.js";

const PROJECT_ID = "demo-synapse-chat";
const FUNCTIONS_REGION = "northamerica-northeast1";
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const PETER_PASSWORD = "peter privacy password";
const TRISH_PASSWORD = "trish privacy password";

let testEnvironment;
let adminApp;
let adminAuth;
let adminFirestore;
let peterApp;
let peterAuth;
let peterFunctions;
let trishApp;
let trishAuth;
let trishFunctions;

async function clearAuthEmulator() {
  const response = await fetch(
    `${AUTH_EMULATOR_URL}/emulator/v1/projects/${PROJECT_ID}/accounts`,
    {method: "DELETE"},
  );
  assert.equal(response.ok, true, `Auth emulator cleanup failed with HTTP ${response.status}.`);
}

async function seedActiveAccount(username, password) {
  const account = await adminAuth.createUser({
    email: `${username}@accounts.synapse.invalid`,
    emailVerified: true,
    password,
  });
  await adminAuth.setCustomUserClaims(account.uid, {
    accountState: "ACTIVE",
    claimsVersion: 1,
    mustChangePassword: false,
    role: "USER",
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
    role: "USER",
    updatedAt: AdminTimestamp.now(),
    username,
    usernameNormalized: username,
  });
  return account;
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8")},
    projectId: PROJECT_ID,
  });
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "privacy-admin-emulator-tests");
  adminAuth = getAdminAuth(adminApp);
  adminFirestore = getAdminFirestore(adminApp);

  peterApp = initializeApp(
    {apiKey: "demo-api-key", appId: "demo-privacy-peter", projectId: PROJECT_ID},
    "privacy-peter-client",
  );
  peterAuth = getAuth(peterApp);
  connectAuthEmulator(peterAuth, AUTH_EMULATOR_URL, {disableWarnings: true});
  peterFunctions = getFunctions(peterApp, FUNCTIONS_REGION);
  connectFunctionsEmulator(peterFunctions, "127.0.0.1", 5001);

  trishApp = initializeApp(
    {apiKey: "demo-api-key", appId: "demo-privacy-trish", projectId: PROJECT_ID},
    "privacy-trish-client",
  );
  trishAuth = getAuth(trishApp);
  connectAuthEmulator(trishAuth, AUTH_EMULATOR_URL, {disableWarnings: true});
  trishFunctions = getFunctions(trishApp, FUNCTIONS_REGION);
  connectFunctionsEmulator(trishFunctions, "127.0.0.1", 5001);
});

beforeEach(async () => {
  if (peterAuth.currentUser) await signOut(peterAuth);
  if (trishAuth.currentUser) await signOut(trishAuth);
  await clearAuthEmulator();
  await testEnvironment.clearFirestore();
});

after(async () => {
  if (peterAuth.currentUser) await signOut(peterAuth);
  if (trishAuth.currentUser) await signOut(trishAuth);
  await deleteApp(peterApp);
  await deleteApp(trishApp);
  await testEnvironment.cleanup();
  await deleteAdminApp(adminApp);
});

test("users block direct-room creation and manage a deletion request", async () => {
  const peter = await seedActiveAccount("peter", PETER_PASSWORD);
  const trish = await seedActiveAccount("trish", TRISH_PASSWORD);
  await signInWithEmailAndPassword(peterAuth, peter.email, PETER_PASSWORD);
  const peterCall = (name) => httpsCallable(peterFunctions, name);

  const privateInstallationId = "private-firebase-installation-id";
  const registration = await peterCall("registerOwnDevice")({installationId: privateInstallationId});
  const peterDeviceId = registration.data.deviceId;
  assert.match(peterDeviceId, /^[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(registration.data).includes(privateInstallationId), false);
  const ownDevices = await peterCall("listOwnDevices")({});
  assert.deepEqual(ownDevices.data.devices, [{
    active: true,
    deviceId: peterDeviceId,
    platform: "ANDROID",
    updatedAtMillis: ownDevices.data.devices[0].updatedAtMillis,
  }]);
  assert.equal(JSON.stringify(ownDevices.data).includes("private-firebase-installation-id"), false);
  assert.equal((await peterCall("removeOwnDevice")({deviceId: peterDeviceId})).data.removed, true);
  assert.equal((await peterCall("removeOwnDevice")({deviceId: peterDeviceId})).data.removed, false);
  assert.equal((await adminFirestore.doc(`devices/${peterDeviceId}`).get()).exists, false);

  const initialPrivacy = await peterCall("getOwnPrivacyState")({});
  assert.deepEqual(initialPrivacy.data, {blockedUids: [], deletionRequestPending: false});
  await peterCall("setUserBlocked")({blocked: true, targetUid: trish.uid});
  const blockedPrivacy = await peterCall("getOwnPrivacyState")({});
  assert.deepEqual(blockedPrivacy.data.blockedUids, [trish.uid]);

  await signInWithEmailAndPassword(trishAuth, trish.email, TRISH_PASSWORD);
  await assert.rejects(
    httpsCallable(trishFunctions, "openDirectRoom")({targetUid: peter.uid}),
    (error) => error.code === "functions/permission-denied",
  );

  await peterCall("setUserBlocked")({blocked: false, targetUid: trish.uid});
  const room = await httpsCallable(trishFunctions, "openDirectRoom")({targetUid: peter.uid});
  assert.match(room.data.roomId, /^direct_[a-f0-9]{64}$/);

  await peterCall("requestAccountDeletion")({});
  assert.equal((await peterCall("getOwnPrivacyState")({})).data.deletionRequestPending, true);
  const deletionRequest = await adminFirestore.doc(`accountDeletionRequests/${peter.uid}`).get();
  assert.equal(deletionRequest.get("state"), "PENDING");
  assert.equal(JSON.stringify(deletionRequest.data()).includes(PETER_PASSWORD), false);
  await peterCall("cancelAccountDeletionRequest")({});
  assert.equal((await peterCall("getOwnPrivacyState")({})).data.deletionRequestPending, false);
});

test("a stale account session cannot request deletion", async () => {
  const peter = await seedActiveAccount("peter", PETER_PASSWORD);
  await assert.rejects(
    requestAccountDeletion.run({
      auth: {
        token: {
          accountState: "ACTIVE",
          auth_time: Math.floor(Date.now() / 1_000) - (5 * 60 + 1),
          mustChangePassword: false,
          role: "USER",
        },
        uid: peter.uid,
      },
      data: {},
    }),
    (error) => error.code === "failed-precondition",
  );
  assert.equal((await adminFirestore.doc(`accountDeletionRequests/${peter.uid}`).get()).exists, false);
});
