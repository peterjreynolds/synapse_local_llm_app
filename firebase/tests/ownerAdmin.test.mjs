import assert from "node:assert/strict";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {getFirestore as getAdminFirestore, Timestamp as AdminTimestamp} from "firebase-admin/firestore";
import {deleteApp, initializeApp} from "firebase/app";
import {
  connectAuthEmulator,
  getAuth,
  getIdTokenResult,
  signInWithEmailAndPassword,
  signOut,
  updatePassword,
} from "firebase/auth";
import {
  collection,
  connectFirestoreEmulator,
  getDocs,
  getFirestore,
  query,
  where,
} from "firebase/firestore";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";
import {createAccountForUser} from "../functions/lib/ownerAccountMutation.js";

const PROJECT_ID = "demo-synapse-chat";
const FUNCTIONS_REGION = "northamerica-northeast1";
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const OWNER_PASSWORD = "owner test password";
const TEMPORARY_PASSWORD = "temporary family password";
const RESET_PASSWORD = "reset family password";
const PERMANENT_PASSWORD = "permanent family password";

let testEnvironment;
let adminApp;
let adminAuth;
let adminFirestore;
let ownerApp;
let ownerAuth;
let userApp;
let userAuth;
let userFirestore;
let ownerFunctions;
let userFunctions;

async function clearAuthEmulator() {
  const response = await fetch(
    `${AUTH_EMULATOR_URL}/emulator/v1/projects/${PROJECT_ID}/accounts`,
    {method: "DELETE"},
  );
  assert.equal(response.ok, true, `Auth emulator cleanup failed with HTTP ${response.status}.`);
}

async function seedIdentity({
  accountState = "ACTIVE",
  allowed = true,
  email,
  password,
  profileRole,
  tokenRole = profileRole,
  username,
}) {
  const account = await adminAuth.createUser({
    disabled: false,
    displayName: username,
    email,
    emailVerified: true,
    password,
  });
  await adminAuth.setCustomUserClaims(account.uid, {
    accountState,
    claimsVersion: 1,
    mustChangePassword: false,
    role: tokenRole,
  });
  await adminFirestore.doc(`profiles/${account.uid}`).create({
    accountState,
    allowed,
    avatarUrl: null,
    bio: "",
    createdAt: AdminTimestamp.now(),
    directoryVisible: allowed,
    displayName: username,
    lastSeenAt: null,
    mustChangePassword: false,
    online: false,
    role: profileRole,
    updatedAt: AdminTimestamp.now(),
    username,
    usernameNormalized: username.toLowerCase(),
  });
  await adminFirestore.doc(`usernames/${username.toLowerCase()}`).create({
    createdAt: AdminTimestamp.now(),
    state: "CLAIMED",
    uid: account.uid,
    usernameNormalized: username.toLowerCase(),
  });
  return account;
}

async function seedOwner() {
  const owner = await seedIdentity({
    email: "peter@accounts.synapse.invalid",
    password: OWNER_PASSWORD,
    profileRole: "OWNER",
    username: "Peter",
  });
  await signInWithEmailAndPassword(ownerAuth, owner.email, OWNER_PASSWORD);
  return owner;
}

function ownerCallable(name) {
  return httpsCallable(ownerFunctions, name);
}

function userCallable(name) {
  return httpsCallable(userFunctions, name);
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {
      rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8"),
    },
    projectId: PROJECT_ID,
  });
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "owner-admin-emulator-tests");
  adminAuth = getAdminAuth(adminApp);
  adminFirestore = getAdminFirestore(adminApp);

  ownerApp = initializeApp(
    {apiKey: "demo-api-key", appId: "demo-owner-admin-app", projectId: PROJECT_ID},
    "owner-admin-client",
  );
  ownerAuth = getAuth(ownerApp);
  connectAuthEmulator(ownerAuth, AUTH_EMULATOR_URL, {disableWarnings: true});
  ownerFunctions = getFunctions(ownerApp, FUNCTIONS_REGION);
  connectFunctionsEmulator(ownerFunctions, "127.0.0.1", 5001);

  userApp = initializeApp(
    {apiKey: "demo-api-key", appId: "demo-owner-user-app", projectId: PROJECT_ID},
    "owner-admin-user-client",
  );
  userAuth = getAuth(userApp);
  connectAuthEmulator(userAuth, AUTH_EMULATOR_URL, {disableWarnings: true});
  userFirestore = getFirestore(userApp);
  connectFirestoreEmulator(userFirestore, "127.0.0.1", 8080);
  userFunctions = getFunctions(userApp, FUNCTIONS_REGION);
  connectFunctionsEmulator(userFunctions, "127.0.0.1", 5001);
});

beforeEach(async () => {
  if (ownerAuth.currentUser) await signOut(ownerAuth);
  if (userAuth.currentUser) await signOut(userAuth);
  await clearAuthEmulator();
  await testEnvironment.clearFirestore();
});

after(async () => {
  if (ownerAuth.currentUser) await signOut(ownerAuth);
  if (userAuth.currentUser) await signOut(userAuth);
  await deleteApp(ownerApp);
  await deleteApp(userApp);
  await testEnvironment.cleanup();
  await deleteAdminApp(adminApp);
});

test("owner completes account, password, device, session, and deletion operations", async () => {
  await seedOwner();
  const created = await ownerCallable("createAccountForUser")({
    displayName: "Josh R.",
    password: TEMPORARY_PASSWORD,
    requirePasswordChange: true,
    username: "josh",
  });
  assert.equal(created.data.usernameNormalized, "josh");
  const targetUid = created.data.targetUid;
  const targetProfile = await adminFirestore.doc(`profiles/${targetUid}`).get();
  assert.equal(targetProfile.get("mustChangePassword"), true);
  assert.equal(JSON.stringify(targetProfile.data()).includes(TEMPORARY_PASSWORD), false);

  const targetCredential = await signInWithEmailAndPassword(
    userAuth,
    "josh@accounts.synapse.invalid",
    TEMPORARY_PASSWORD,
  );
  const temporaryToken = await getIdTokenResult(targetCredential.user, true);
  assert.equal(temporaryToken.claims.mustChangePassword, true);
  await assertFails(
    getDocs(
      query(
        collection(userFirestore, "profiles"),
        where("allowed", "==", true),
        where("accountState", "==", "ACTIVE"),
        where("directoryVisible", "==", true),
      ),
    ),
  );

  await updatePassword(targetCredential.user, PERMANENT_PASSWORD);
  await getIdTokenResult(targetCredential.user, true);
  await userCallable("completeRequiredPasswordChange")({});
  const permanentToken = await getIdTokenResult(targetCredential.user, true);
  assert.equal(permanentToken.claims.mustChangePassword, false);
  await assertSucceeds(
    getDocs(
      query(
        collection(userFirestore, "profiles"),
        where("allowed", "==", true),
        where("accountState", "==", "ACTIVE"),
        where("directoryVisible", "==", true),
      ),
    ),
  );

  await ownerCallable("resetOwnerAccountPassword")({
    password: RESET_PASSWORD,
    requirePasswordChange: true,
    targetUid,
  });
  await signOut(userAuth);
  const resetCredential = await signInWithEmailAndPassword(
    userAuth,
    "josh@accounts.synapse.invalid",
    RESET_PASSWORD,
  );
  assert.equal((await getIdTokenResult(resetCredential.user, true)).claims.mustChangePassword, true);
  await signOut(userAuth);

  await ownerCallable("setOwnerAccountEnabled")({enabled: false, targetUid});
  assert.equal((await adminAuth.getUser(targetUid)).disabled, true);
  await assert.rejects(signInWithEmailAndPassword(userAuth, "josh@accounts.synapse.invalid", RESET_PASSWORD));
  await ownerCallable("setOwnerAccountEnabled")({enabled: true, targetUid});
  assert.equal((await adminAuth.getUser(targetUid)).disabled, false);

  await ownerCallable("revokeOwnerAccountSessions")({targetUid});
  const revokedAccount = await adminAuth.getUser(targetUid);
  assert.notEqual(revokedAccount.tokensValidAfterTime, undefined);

  await adminFirestore.doc(`devices/${"a".repeat(64)}`).create({
    active: true,
    createdAt: AdminTimestamp.now(),
    installationId: "test-firebase-installation-id",
    ownerUid: targetUid,
    platform: "ANDROID",
    updatedAt: AdminTimestamp.now(),
  });
  const devices = await ownerCallable("listOwnerDevices")({targetUid});
  assert.equal(devices.data.devices.length, 1);
  assert.equal("installationId" in devices.data.devices[0], false);
  await ownerCallable("removeOwnerDevice")({deviceId: "a".repeat(64), targetUid});
  assert.equal((await adminFirestore.doc(`devices/${"a".repeat(64)}`).get()).exists, false);

  const accounts = await ownerCallable("listOwnerAccounts")({searchPrefix: "josh"});
  assert.equal(accounts.data.accounts.some((account) => account.uid === targetUid), true);
  assert.equal(
    (await ownerCallable("getOwnerRegistrationConfiguration")({})).data.approvalRequired,
    true,
  );
  await ownerCallable("setRegistrationApprovalRequired")({approvalRequired: false});
  assert.equal(
    (await ownerCallable("getOwnerRegistrationConfiguration")({})).data.approvalRequired,
    false,
  );
  const audit = await ownerCallable("listOwnerAuditEvents")({limit: 100});
  assert.equal(audit.data.events.some((event) => event.eventType === "ACCOUNT_PASSWORD_RESET"), true);
  assert.equal(JSON.stringify(audit.data).includes(RESET_PASSWORD), false);

  await adminFirestore.doc("blocks/owner-cleanup-block").create({
    blockedUid: ownerAuth.currentUser.uid,
    blockerUid: targetUid,
    createdAt: AdminTimestamp.now(),
  });
  await adminFirestore.doc(`accountDeletionRequests/${targetUid}`).create({
    requestedAt: AdminTimestamp.now(),
    requestedBy: targetUid,
    state: "PENDING",
  });

  await ownerCallable("deleteOwnerAccount")({confirmUsername: "josh", targetUid});
  await assert.rejects(adminAuth.getUser(targetUid));
  assert.equal((await adminFirestore.doc(`profiles/${targetUid}`).get()).exists, false);
  assert.equal((await adminFirestore.doc("usernames/josh").get()).exists, false);
  assert.equal((await adminFirestore.doc("blocks/owner-cleanup-block").get()).exists, false);
  assert.equal((await adminFirestore.doc(`accountDeletionRequests/${targetUid}`).get()).exists, false);
});

test("normal, pending, disabled, and claim-only owners cannot call owner functions", async () => {
  await seedOwner();
  const normal = await seedIdentity({
    email: "normal@accounts.synapse.invalid",
    password: "normal family password",
    profileRole: "USER",
    username: "Normal",
  });
  await signInWithEmailAndPassword(userAuth, normal.email, "normal family password");
  await assert.rejects(
    userCallable("listOwnerAccounts")({}),
    (error) => error.code === "functions/permission-denied",
  );
  await signOut(userAuth);

  const pending = await seedIdentity({
    accountState: "PENDING_APPROVAL",
    allowed: false,
    email: "pending@accounts.synapse.invalid",
    password: "pending family password",
    profileRole: "USER",
    username: "Pending",
  });
  await signInWithEmailAndPassword(userAuth, pending.email, "pending family password");
  await assert.rejects(
    userCallable("createInvitation")({intendedLabel: null, lifetimeHours: 24, maximumUses: 1}),
    (error) => error.code === "functions/permission-denied",
  );
  await signOut(userAuth);

  const claimOnlyOwner = await seedIdentity({
    email: "stolen@accounts.synapse.invalid",
    password: "stolen family password",
    profileRole: "USER",
    tokenRole: "OWNER",
    username: "Stolen",
  });
  await signInWithEmailAndPassword(userAuth, claimOnlyOwner.email, "stolen family password");
  await assert.rejects(
    userCallable("listOwnerAuditEvents")({limit: 10}),
    (error) => error.code === "functions/permission-denied",
  );

  await adminFirestore.doc(`profiles/${claimOnlyOwner.uid}`).update({
    accountState: "DISABLED",
    allowed: false,
  });
  await assert.rejects(
    userCallable("listOwnerDevices")({targetUid: normal.uid}),
    (error) => error.code === "functions/permission-denied",
  );
  await signOut(userAuth);

  const forcedPasswordOwner = await seedIdentity({
    email: "forced-owner@accounts.synapse.invalid",
    password: "forced owner password",
    profileRole: "OWNER",
    username: "ForcedOwner",
  });
  await adminAuth.setCustomUserClaims(forcedPasswordOwner.uid, {
    accountState: "ACTIVE",
    claimsVersion: 1,
    mustChangePassword: true,
    role: "OWNER",
  });
  await adminFirestore.doc(`profiles/${forcedPasswordOwner.uid}`).update({mustChangePassword: true});
  await signInWithEmailAndPassword(userAuth, forcedPasswordOwner.email, "forced owner password");
  await assert.rejects(
    userCallable("listOwnerAccounts")({}),
    (error) => error.code === "functions/permission-denied",
  );
});

test("persisted revocation state rejects an already-issued account token", async () => {
  await seedOwner();
  const account = await seedIdentity({
    email: "revoked@accounts.synapse.invalid",
    password: "revoked family password",
    profileRole: "USER",
    username: "Revoked",
  });
  await signInWithEmailAndPassword(userAuth, account.email, "revoked family password");
  assert.deepEqual((await userCallable("getOwnPrivacyState")({})).data, {
    blockedUids: [],
    deletionRequestPending: false,
  });

  await ownerCallable("revokeOwnerAccountSessions")({targetUid: account.uid});
  const profile = await adminFirestore.doc(`profiles/${account.uid}`).get();
  assert.equal(profile.get("sessionsRevokedAt") instanceof AdminTimestamp, true);
  await assert.rejects(
    userCallable("getOwnPrivacyState")({}),
    (error) => error.code === "functions/permission-denied",
  );
});

test("owner operations summary reports bounded backend, delivery, and integrity state", async () => {
  const owner = await seedOwner();
  const account = await seedIdentity({
    email: "operations@accounts.synapse.invalid",
    password: "operations family password",
    profileRole: "USER",
    username: "Operations",
  });
  await adminFirestore.doc(`devices/${"a".repeat(64)}`).create({
    active: true,
    installationId: "active-installation-id",
    ownerUid: owner.uid,
    platform: "ANDROID",
    updatedAt: AdminTimestamp.now(),
  });
  await adminFirestore.doc(`devices/${"b".repeat(64)}`).create({
    active: false,
    installationId: "inactive-installation-id",
    ownerUid: account.uid,
    platform: "ANDROID",
    updatedAt: AdminTimestamp.now(),
  });
  await adminFirestore.doc("notificationDeliveries/pending").create({
    startedAt: AdminTimestamp.now(),
    state: "PROCESSING",
  });
  await adminFirestore.doc("notificationDeliveries/failed").create({
    completedAt: AdminTimestamp.now(),
    failureCount: 1,
    startedAt: AdminTimestamp.now(),
    state: "COMPLETE",
  });
  await adminFirestore.doc("rooms/direct_operations").create({
    activeMemberIds: [owner.uid, account.uid],
    deletedAt: null,
    kind: "DIRECT",
    memberIds: [owner.uid, account.uid],
  });
  await Promise.all([owner.uid, account.uid].map((uid) =>
    adminFirestore.doc(`rooms/direct_operations/members/${uid}`).create({
      active: true,
      role: "MEMBER",
      uid,
    }),
  ));
  await adminFirestore.doc("operationsJobStatus/operationalDataCleanup").create({
    affectedDocumentCount: 7,
    lastCompletedAt: AdminTimestamp.now(),
    lastStartedAt: AdminTimestamp.now(),
    state: "SUCCEEDED",
  });

  const summary = (await ownerCallable("getOwnerOperationsSummary")({})).data;
  assert.equal(summary.backendState, "HEALTHY");
  assert.match(summary.backendRevision, /^[A-Za-z0-9._-]{1,128}$/);
  assert.equal(summary.totalDeviceCount, 2);
  assert.equal(summary.activeDeviceCount, 1);
  assert.equal(summary.activeRoomCount, 1);
  assert.equal(summary.pendingNotificationDeliveryCount, 1);
  assert.equal(summary.failedNotificationDeliveryCount, 1);
  assert.deepEqual(summary.integrity.issueCodes, []);
  assert.equal(summary.integrity.issueCount, 0);
  assert.equal(summary.attachmentCleanup.state, "NEVER_RUN");
  assert.equal(summary.operationalDataCleanup.state, "SUCCEEDED");
  assert.equal(summary.operationalDataCleanup.affectedDocumentCount, 7);
});

test("a stale owner session cannot call a recent-auth owner function", async () => {
  const owner = await seedOwner();
  await assert.rejects(
    createAccountForUser.run({
      auth: {
        token: {
          accountState: "ACTIVE",
          auth_time: Math.floor(Date.now() / 1_000) - (5 * 60 + 1),
          mustChangePassword: false,
          role: "OWNER",
        },
        uid: owner.uid,
      },
      data: {
        displayName: "Stale Attempt",
        password: "unused temporary password",
        requirePasswordChange: true,
        username: "stale-attempt",
      },
    }),
    (error) => error.code === "failed-precondition",
  );
  assert.equal((await adminFirestore.doc("usernames/stale-attempt").get()).exists, false);
});
