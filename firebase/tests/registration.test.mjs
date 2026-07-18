import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {deleteApp, initializeApp} from "firebase/app";
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  getIdTokenResult,
  signInWithEmailAndPassword,
  signOut,
} from "firebase/auth";
import {
  Timestamp,
  collection,
  connectFirestoreEmulator,
  doc,
  getDoc,
  getDocs,
  getFirestore,
  query,
  setDoc,
  where,
} from "firebase/firestore";
import {connectFunctionsEmulator, getFunctions, httpsCallable} from "firebase/functions";

const PROJECT_ID = "demo-synapse-chat";
const FUNCTIONS_REGION = "northamerica-northeast1";
const FIRESTORE_HOST = "127.0.0.1";
const FIRESTORE_PORT = 8080;
const AUTH_EMULATOR_URL = "http://127.0.0.1:9099";
const FUNCTIONS_PORT = 5001;
const REGISTRATION_FAILURE =
  "Registration could not be completed. Check the invitation and account details.";

let testEnvironment;
let clientApp;
let clientAuth;
let clientFirestore;
let registerWithInvite;
let createInvitation;
let reviewRegistration;
let setRegistrationApprovalRequired;
let adminApp;
let adminAuth;
let ownerApp;
let ownerAuth;
let identitySequence = 0;

function nextIdentity(label) {
  identitySequence += 1;
  return `${label}_${identitySequence}`;
}

function invitationCode(label) {
  return `invite_${label}_${"x".repeat(40)}`;
}

function invitationId(code) {
  return createHash("sha256").update(code, "utf8").digest("hex");
}

function registrationPayload(username, code) {
  return {
    displayName: `Display ${username}`,
    invitationCode: code,
    password: "a secure family password",
    username,
  };
}

async function seedInvitation(code, overrides = {}) {
  const defaults = {
    createdAt: Timestamp.now(),
    creatorUid: "owner-uid",
    expiresAt: Timestamp.fromMillis(Date.now() + 60 * 60 * 1000),
    intendedLabel: null,
    maximumUses: 1,
    redeemedCount: 0,
    remainingUses: 1,
    revokedAt: null,
    state: "ACTIVE",
  };
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "invitations", invitationId(code)),
      {...defaults, ...overrides},
    );
  });
}

async function readServerDocument(path) {
  let snapshot;
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    snapshot = await getDoc(doc(context.firestore(), path));
  });
  return snapshot;
}

async function clearAuthEmulator() {
  const response = await fetch(
    `${AUTH_EMULATOR_URL}/emulator/v1/projects/${PROJECT_ID}/accounts`,
    {method: "DELETE"},
  );
  assert.equal(response.ok, true, `Auth emulator cleanup failed with HTTP ${response.status}.`);
}

async function seedOwner() {
  const owner = await adminAuth.createUser({
    displayName: "Peter",
    email: "peter@accounts.synapse.invalid",
    emailVerified: true,
    password: "owner test password",
  });
  await adminAuth.setCustomUserClaims(owner.uid, {
    accountState: "ACTIVE",
    claimsVersion: 1,
    mustChangePassword: false,
    role: "OWNER",
  });
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "profiles", owner.uid), {
      accountState: "ACTIVE",
      allowed: true,
      avatarUrl: null,
      bio: "",
      createdAt: Timestamp.now(),
      directoryVisible: true,
      displayName: "Peter",
      lastSeenAt: null,
      mustChangePassword: false,
      online: false,
      role: "OWNER",
      updatedAt: Timestamp.now(),
      username: "Peter",
      usernameNormalized: "peter",
    });
  });
  await signInWithEmailAndPassword(
    ownerAuth,
    "peter@accounts.synapse.invalid",
    "owner test password",
  );
  return owner;
}

before(async () => {
  adminApp = initializeAdminApp({projectId: PROJECT_ID}, "registration-emulator-admin");
  adminAuth = getAdminAuth(adminApp);
  testEnvironment = await initializeTestEnvironment({
    firestore: {
      rules: fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8"),
    },
    projectId: PROJECT_ID,
  });
  clientApp = initializeApp(
    {apiKey: "demo-api-key", appId: "demo-app-id", projectId: PROJECT_ID},
    "registration-emulator-tests",
  );
  clientAuth = getAuth(clientApp);
  connectAuthEmulator(clientAuth, AUTH_EMULATOR_URL, {disableWarnings: true});
  clientFirestore = getFirestore(clientApp);
  connectFirestoreEmulator(clientFirestore, FIRESTORE_HOST, FIRESTORE_PORT);
  const functions = getFunctions(clientApp, FUNCTIONS_REGION);
  connectFunctionsEmulator(functions, FIRESTORE_HOST, FUNCTIONS_PORT);
  registerWithInvite = httpsCallable(functions, "registerWithInvite");
  ownerApp = initializeApp(
    {apiKey: "demo-api-key", appId: "demo-owner-app-id", projectId: PROJECT_ID},
    "registration-owner-tests",
  );
  ownerAuth = getAuth(ownerApp);
  connectAuthEmulator(ownerAuth, AUTH_EMULATOR_URL, {disableWarnings: true});
  const ownerFunctions = getFunctions(ownerApp, FUNCTIONS_REGION);
  connectFunctionsEmulator(ownerFunctions, FIRESTORE_HOST, FUNCTIONS_PORT);
  createInvitation = httpsCallable(ownerFunctions, "createInvitation");
  reviewRegistration = httpsCallable(ownerFunctions, "reviewRegistration");
  setRegistrationApprovalRequired = httpsCallable(
    ownerFunctions,
    "setRegistrationApprovalRequired",
  );
});

beforeEach(async () => {
  if (clientAuth.currentUser) await signOut(clientAuth);
  if (ownerAuth.currentUser) await signOut(ownerAuth);
  await clearAuthEmulator();
  await testEnvironment.clearFirestore();
});

after(async () => {
  if (clientAuth.currentUser) await signOut(clientAuth);
  if (ownerAuth.currentUser) await signOut(ownerAuth);
  await deleteApp(ownerApp);
  await deleteApp(clientApp);
  await testEnvironment.cleanup();
  await deleteAdminApp(adminApp);
});

test("registers an invited account as pending and denies chat access", async () => {
  const username = nextIdentity("pending");
  const code = invitationCode(username);
  await seedInvitation(code);

  const registration = await registerWithInvite(registrationPayload(username, code));
  assert.deepEqual(registration.data, {
    accountState: "PENDING_APPROVAL",
    usernameNormalized: username,
  });

  const credential = await signInWithEmailAndPassword(
    clientAuth,
    `${username}@accounts.synapse.invalid`,
    "a secure family password",
  );
  const token = await getIdTokenResult(credential.user, true);
  assert.equal(token.claims.accountState, "PENDING_APPROVAL");
  assert.equal(token.claims.role, "USER");
  await assertSucceeds(getDoc(doc(clientFirestore, "profiles", credential.user.uid)));
  await assertFails(getDocs(collection(clientFirestore, "profiles")));
  await assertFails(getDocs(collection(clientFirestore, "rooms")));
});

test("rejects invalid, expired, and revoked invitations with one generic error", async () => {
  const cases = [
    {label: "missing", seed: false},
    {
      label: "expired",
      overrides: {expiresAt: Timestamp.fromMillis(Date.now() - 1)},
      seed: true,
    },
    {
      label: "revoked",
      overrides: {revokedAt: Timestamp.now(), state: "REVOKED"},
      seed: true,
    },
  ];
  for (const invitationCase of cases) {
    const username = nextIdentity(invitationCase.label);
    const code = invitationCode(username);
    if (invitationCase.seed) await seedInvitation(code, invitationCase.overrides);
    await assert.rejects(
      registerWithInvite(registrationPayload(username, code)),
      (error) => error.code === "functions/permission-denied" && error.message === REGISTRATION_FAILURE,
    );
  }
});

test("consumes a single-use invitation exactly once", async () => {
  const code = invitationCode(nextIdentity("single_use_invite"));
  const firstUsername = nextIdentity("single_use_first");
  const secondUsername = nextIdentity("single_use_second");
  await seedInvitation(code);

  await registerWithInvite(registrationPayload(firstUsername, code));
  await assert.rejects(
    registerWithInvite(registrationPayload(secondUsername, code)),
    (error) => error.code === "functions/permission-denied" && error.message === REGISTRATION_FAILURE,
  );
  const invitation = await readServerDocument(`invitations/${invitationId(code)}`);
  assert.equal(invitation.get("remainingUses"), 0);
  assert.equal(invitation.get("state"), "EXHAUSTED");
});

test("resolves duplicate username races without consuming both invitations", async () => {
  const username = nextIdentity("username_race");
  const firstCode = invitationCode(nextIdentity("race_a"));
  const secondCode = invitationCode(nextIdentity("race_b"));
  await seedInvitation(firstCode);
  await seedInvitation(secondCode);

  const outcomes = await Promise.allSettled([
    registerWithInvite(registrationPayload(username, firstCode)),
    registerWithInvite(registrationPayload(username, secondCode)),
  ]);
  assert.equal(outcomes.filter((outcome) => outcome.status === "fulfilled").length, 1);
  assert.equal(outcomes.filter((outcome) => outcome.status === "rejected").length, 1);
  const firstInvite = await readServerDocument(`invitations/${invitationId(firstCode)}`);
  const secondInvite = await readServerDocument(`invitations/${invitationId(secondCode)}`);
  const remainingUses = [firstInvite.get("remainingUses"), secondInvite.get("remainingUses")].sort();
  assert.deepEqual(remainingUses, [0, 1]);
  const usernameRecord = await readServerDocument(`usernames/${username}`);
  assert.equal(usernameRecord.get("state"), "CLAIMED");
});

test("restores invitation and username reservation when Auth creation fails", async () => {
  const username = nextIdentity("auth_collision");
  const code = invitationCode(username);
  await seedInvitation(code);
  await createUserWithEmailAndPassword(
    clientAuth,
    `${username}@accounts.synapse.invalid`,
    "an existing auth password",
  );
  await signOut(clientAuth);

  await assert.rejects(
    registerWithInvite(registrationPayload(username, code)),
    (error) => error.code === "functions/internal" && error.message === REGISTRATION_FAILURE,
  );
  const usernameRecord = await readServerDocument(`usernames/${username}`);
  const invitation = await readServerDocument(`invitations/${invitationId(code)}`);
  assert.equal(usernameRecord.exists(), false);
  assert.equal(invitation.get("remainingUses"), 1);
  assert.equal(invitation.get("state"), "ACTIVE");
});

test("honors owner-configured registration without manual approval", async () => {
  const username = nextIdentity("auto_approved");
  const code = invitationCode(username);
  await seedInvitation(code);
  await seedOwner();
  const approvalPolicy = await setRegistrationApprovalRequired({approvalRequired: false});
  assert.deepEqual(approvalPolicy.data, {approvalRequired: false});

  const registration = await registerWithInvite(registrationPayload(username, code));
  assert.equal(registration.data.accountState, "ACTIVE");
  const credential = await signInWithEmailAndPassword(
    clientAuth,
    `${username}@accounts.synapse.invalid`,
    "a secure family password",
  );
  const token = await getIdTokenResult(credential.user, true);
  assert.equal(token.claims.accountState, "ACTIVE");
  await assertSucceeds(
    getDocs(
      query(
        collection(clientFirestore, "profiles"),
        where("allowed", "==", true),
        where("accountState", "==", "ACTIVE"),
        where("directoryVisible", "==", true),
      ),
    ),
  );
});

test("creates a digest-only invitation and refreshes claims after owner approval", async () => {
  await seedOwner();
  const invitation = await createInvitation({
    intendedLabel: "Approval test",
    lifetimeHours: 24,
    maximumUses: 1,
  });
  assert.match(invitation.data.invitationCode, /^[A-Za-z0-9_-]{32,128}$/);
  assert.match(invitation.data.invitationId, /^[a-f0-9]{64}$/);
  const invitationRecord = await readServerDocument(`invitations/${invitation.data.invitationId}`);
  assert.equal(invitationRecord.exists(), true);
  assert.equal(JSON.stringify(invitationRecord.data()).includes(invitation.data.invitationCode), false);

  const username = nextIdentity("approved");
  await registerWithInvite(
    registrationPayload(username, invitation.data.invitationCode),
  );
  const credential = await signInWithEmailAndPassword(
    clientAuth,
    `${username}@accounts.synapse.invalid`,
    "a secure family password",
  );
  const pendingToken = await getIdTokenResult(credential.user, true);
  assert.equal(pendingToken.claims.accountState, "PENDING_APPROVAL");

  const review = await reviewRegistration({decision: "APPROVE", targetUid: credential.user.uid});
  assert.deepEqual(review.data, {accountState: "ACTIVE", targetUid: credential.user.uid});
  const approvedToken = await getIdTokenResult(credential.user, true);
  assert.equal(approvedToken.claims.accountState, "ACTIVE");
  await assertSucceeds(
    getDocs(
      query(
        collection(clientFirestore, "profiles"),
        where("allowed", "==", true),
        where("accountState", "==", "ACTIVE"),
        where("directoryVisible", "==", true),
      ),
    ),
  );
});

test("keeps rejected registrations limited to their own status", async () => {
  await seedOwner();
  const code = invitationCode(nextIdentity("rejected_invite"));
  const username = nextIdentity("rejected");
  await seedInvitation(code);
  await registerWithInvite(registrationPayload(username, code));
  const credential = await signInWithEmailAndPassword(
    clientAuth,
    `${username}@accounts.synapse.invalid`,
    "a secure family password",
  );

  const review = await reviewRegistration({decision: "REJECT", targetUid: credential.user.uid});
  assert.deepEqual(review.data, {accountState: "REJECTED", targetUid: credential.user.uid});
  const rejectedToken = await getIdTokenResult(credential.user, true);
  assert.equal(rejectedToken.claims.accountState, "REJECTED");
  await assertSucceeds(getDoc(doc(clientFirestore, "profiles", credential.user.uid)));
  await assertFails(getDocs(collection(clientFirestore, "profiles")));
  await assertFails(getDocs(collection(clientFirestore, "rooms")));
});

test("rate limits repeated registration attempts without storing the raw address", async () => {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    await assert.rejects(
      registerWithInvite({}),
      (error) => error.code === "functions/invalid-argument",
    );
  }
  await assert.rejects(
    registerWithInvite({}),
    (error) => error.code === "functions/resource-exhausted",
  );
  let rateLimitRecords;
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    rateLimitRecords = await getDocs(collection(context.firestore(), "registrationRateLimits"));
  });
  assert.equal(rateLimitRecords.size, 1);
  assert.match(rateLimitRecords.docs[0].id, /^[a-f0-9]{64}$/);
  assert.equal(rateLimitRecords.docs[0].data().attemptCount, 10);
});
