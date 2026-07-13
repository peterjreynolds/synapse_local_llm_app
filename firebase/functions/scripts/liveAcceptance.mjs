import assert from "node:assert/strict";
import {randomBytes} from "node:crypto";
import {readFile} from "node:fs/promises";
import {setTimeout as delay} from "node:timers/promises";
import {applicationDefault, deleteApp as deleteAdminApp, initializeApp as initializeAdminApp} from "firebase-admin/app";
import {getAuth as getAdminAuth} from "firebase-admin/auth";
import {FieldValue as AdminFieldValue, getFirestore as getAdminFirestore} from "firebase-admin/firestore";
import {deleteApp as deleteClientApp, initializeApp as initializeClientApp} from "firebase/app";
import {getAuth as getClientAuth, signInWithEmailAndPassword, signOut} from "firebase/auth";
import {
  collection,
  disableNetwork,
  doc,
  enableNetwork,
  getDoc,
  getDocs,
  initializeFirestore,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  terminate,
  Timestamp,
  where,
} from "firebase/firestore";
import {getFunctions, httpsCallable} from "firebase/functions";

const PROJECT_ID = process.env.GOOGLE_CLOUD_PROJECT ?? "synapse-chat-pjr-2026";
const ACCEPTANCE_ENABLED = process.env.SYNAPSE_LIVE_ACCEPTANCE === "1";
const REGION = "northamerica-northeast1";
const PACKAGE_NAME = "app.synapse.localllm.debug";
const WAIT_TIMEOUT_MILLIS = 45_000;

function requireLiveAcceptanceAuthorization() {
  if (!ACCEPTANCE_ENABLED) {
    throw new Error("Set SYNAPSE_LIVE_ACCEPTANCE=1 to authorize production acceptance mutations.");
  }
  if (PROJECT_ID !== "synapse-chat-pjr-2026") {
    throw new Error(`Refusing live acceptance against unexpected project ${PROJECT_ID}.`);
  }
}

async function loadClientConfig() {
  const googleServices = JSON.parse(
    await readFile(new URL("../../../app/google-services.json", import.meta.url), "utf8"),
  );
  const client = googleServices.client.find(
    (candidate) => candidate.client_info.android_client_info.package_name === PACKAGE_NAME,
  );
  assert(client, `No Firebase client configuration exists for ${PACKAGE_NAME}.`);
  const apiKey = client.api_key[0]?.current_key;
  assert(apiKey, "Firebase client configuration does not contain an API key.");
  return {
    apiKey,
    appId: client.client_info.mobilesdk_app_id,
    authDomain: `${PROJECT_ID}.firebaseapp.com`,
    messagingSenderId: googleServices.project_info.project_number,
    projectId: PROJECT_ID,
    storageBucket: googleServices.project_info.storage_bucket,
  };
}

function waitForQueryDocument(queryReference, predicate, label) {
  return new Promise((resolve, reject) => {
    let unsubscribe = () => {};
    const timer = setTimeout(() => {
      unsubscribe();
      reject(new Error(`Timed out waiting for ${label}.`));
    }, WAIT_TIMEOUT_MILLIS);
    unsubscribe = onSnapshot(
      queryReference,
      {includeMetadataChanges: true},
      (snapshot) => {
        const match = snapshot.docs.find(predicate);
        if (match) {
          clearTimeout(timer);
          unsubscribe();
          resolve(match);
        }
      },
      (error) => {
        clearTimeout(timer);
        unsubscribe();
        reject(error);
      },
    );
  });
}

function waitForPendingDocument(documentReference, label) {
  return new Promise((resolve, reject) => {
    let unsubscribe = () => {};
    const timer = setTimeout(() => {
      unsubscribe();
      reject(new Error(`Timed out waiting for ${label}.`));
    }, WAIT_TIMEOUT_MILLIS);
    unsubscribe = onSnapshot(
      documentReference,
      {includeMetadataChanges: true},
      (snapshot) => {
        if (snapshot.exists() && snapshot.metadata.hasPendingWrites) {
          clearTimeout(timer);
          unsubscribe();
          resolve(snapshot);
        }
      },
      (error) => {
        clearTimeout(timer);
        unsubscribe();
        reject(error);
      },
    );
  });
}

async function waitForAdminState(readState, predicate, label) {
  const deadline = Date.now() + WAIT_TIMEOUT_MILLIS;
  while (Date.now() < deadline) {
    const state = await readState();
    if (predicate(state)) return state;
    await delay(500);
  }
  throw new Error(`Timed out waiting for ${label}.`);
}

async function createAcceptanceIdentity(adminAuth, adminFirestore, role, runId) {
  const emailSafeRunId = runId.replaceAll("_", "-");
  const email = `acceptance-${role}-${emailSafeRunId}@accounts.synapse.invalid`;
  const password = `${randomBytes(36).toString("base64url")}aA1!`;
  const displayName = role === "peter" ? "Acceptance Peter" : "Acceptance Trish";
  const user = await adminAuth.createUser({
    disabled: false,
    displayName,
    email,
    emailVerified: true,
    password,
  });
  try {
    await adminFirestore.doc(`profiles/${user.uid}`).create({
      allowed: true,
      avatarUrl: null,
      bio: "Temporary release acceptance account",
      createdAt: AdminFieldValue.serverTimestamp(),
      directoryVisible: false,
      displayName,
      lastSeenAt: null,
      online: false,
      updatedAt: AdminFieldValue.serverTimestamp(),
      username: `acceptance_${role}`,
      usernameNormalized: `acceptance_${role}`,
    });
  } catch (error) {
    await adminAuth.deleteUser(user.uid);
    throw error;
  }
  return {email, password, uid: user.uid};
}

async function signInAcceptanceClient(clientAuth, identity) {
  const credential = await signInWithEmailAndPassword(clientAuth, identity.email, identity.password);
  assert.equal(credential.user.uid, identity.uid);
}

function buildMessagePayload(messageId, senderUid, body) {
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

async function waitForNotificationReceipt(adminFirestore, messageId) {
  return waitForAdminState(
    async () => {
      const snapshot = await adminFirestore
        .collection("notificationDeliveries")
        .where("messageId", "==", messageId)
        .limit(1)
        .get();
      return snapshot.docs[0] ?? null;
    },
    (snapshot) => snapshot?.get("state") === "COMPLETE",
    `completed notification receipt for ${messageId}`,
  );
}

async function main() {
  requireLiveAcceptanceAuthorization();
  const runId = `live_${Date.now()}`;
  const gitSha = process.env.SYNAPSE_ACCEPTANCE_GIT_SHA ?? "unknown";
  const adminApp = initializeAdminApp(
    {credential: applicationDefault(), projectId: PROJECT_ID},
    `synapse-live-acceptance-${runId}`,
  );
  const adminAuth = getAdminAuth(adminApp);
  const adminFirestore = getAdminFirestore(adminApp);
  const receiptReference = adminFirestore.collection("releaseAcceptanceReceipts").doc(runId);
  const clientApps = [];
  const clientFirestores = [];
  const acceptanceIdentities = [];
  let roomId = null;
  const messageIds = [];

  try {
    const [provisionedPeter, provisionedTrish, clientConfig] = await Promise.all([
      adminAuth.getUserByEmail("peter@accounts.synapse.invalid"),
      adminAuth.getUserByEmail("trish@accounts.synapse.invalid"),
      loadClientConfig(),
    ]);
    assert.equal(provisionedPeter.disabled, false, "Peter must be enabled.");
    assert.equal(provisionedTrish.disabled, false, "Trish must be enabled.");

    const [peterProfile, trishProfile] = await adminFirestore.getAll(
      adminFirestore.doc(`profiles/${provisionedPeter.uid}`),
      adminFirestore.doc(`profiles/${provisionedTrish.uid}`),
    );
    assert.equal(peterProfile.get("allowed"), true, "Peter must be allowed.");
    assert.equal(trishProfile.get("allowed"), true, "Trish must be allowed.");

    const peter = await createAcceptanceIdentity(adminAuth, adminFirestore, "peter", runId);
    acceptanceIdentities.push(peter);
    const trish = await createAcceptanceIdentity(adminAuth, adminFirestore, "trish", runId);
    acceptanceIdentities.push(trish);

    const peterApp = initializeClientApp(clientConfig, `peter-${runId}`);
    const trishApp = initializeClientApp(clientConfig, `trish-${runId}`);
    clientApps.push(peterApp, trishApp);
    const peterFirestore = initializeFirestore(peterApp, {experimentalForceLongPolling: true});
    const trishFirestore = initializeFirestore(trishApp, {experimentalForceLongPolling: true});
    clientFirestores.push(peterFirestore, trishFirestore);
    const peterAuth = getClientAuth(peterApp);
    const trishAuth = getClientAuth(trishApp);
    const peterFunctions = getFunctions(peterApp, REGION);
    const trishFunctions = getFunctions(trishApp, REGION);

    await Promise.all([
      signInAcceptanceClient(peterAuth, peter),
      signInAcceptanceClient(trishAuth, trish),
    ]);

    const [peterOpenResult, trishOpenResult] = await Promise.all([
      httpsCallable(peterFunctions, "openDirectRoom")({targetUid: trish.uid}),
      httpsCallable(trishFunctions, "openDirectRoom")({targetUid: peter.uid}),
    ]);
    roomId = peterOpenResult.data.roomId;
    assert.match(roomId, /^direct_[a-f0-9]{64}$/);
    assert.equal(trishOpenResult.data.roomId, roomId, "Direct room creation must be deterministic.");

    const peterRoomReference = doc(peterFirestore, "rooms", roomId);
    const trishRoomReference = doc(trishFirestore, "rooms", roomId);
    const [peterRoom, trishRoom, peterRoomList, trishRoomList] = await Promise.all([
      getDoc(peterRoomReference),
      getDoc(trishRoomReference),
      getDocs(query(collection(peterFirestore, "rooms"), where("memberIds", "array-contains", peter.uid))),
      getDocs(query(collection(trishFirestore, "rooms"), where("memberIds", "array-contains", trish.uid))),
    ]);
    assert(peterRoom.exists() && trishRoom.exists(), "Both clients must read the direct room.");
    assert(peterRoomList.docs.some((snapshot) => snapshot.id === roomId));
    assert(trishRoomList.docs.some((snapshot) => snapshot.id === roomId));

    const markPeterRead = httpsCallable(peterFunctions, "markRoomRead");
    const markTrishRead = httpsCallable(trishFunctions, "markRoomRead");
    await Promise.all([markPeterRead({roomId}), markTrishRead({roomId})]);

    const roomMessages = (firestore) =>
      query(collection(firestore, "rooms", roomId, "messages"), orderBy("createdAt", "asc"));
    const peterMemberReference = adminFirestore.doc(`rooms/${roomId}/members/${peter.uid}`);
    const trishMemberReference = adminFirestore.doc(`rooms/${roomId}/members/${trish.uid}`);

    const peterMessageId = `${runId}_peter_to_trish`;
    messageIds.push(peterMessageId);
    const trishObservedPeterMessage = waitForQueryDocument(
      roomMessages(trishFirestore),
      (snapshot) => snapshot.id === peterMessageId && !snapshot.metadata.hasPendingWrites,
      "Trish realtime receipt of Peter's message",
    );
    await setDoc(
      doc(peterFirestore, "rooms", roomId, "messages", peterMessageId),
      buildMessagePayload(peterMessageId, peter.uid, `Release acceptance ${runId}: Peter to Trish.`),
    );
    await Promise.all([trishObservedPeterMessage, waitForNotificationReceipt(adminFirestore, peterMessageId)]);
    await waitForAdminState(
      async () => (await trishMemberReference.get()).get("unreadCount"),
      (unreadCount) => unreadCount === 1,
      "Trish unread count increment",
    );
    await markTrishRead({roomId});
    await waitForAdminState(
      async () => (await trishMemberReference.get()).get("unreadCount"),
      (unreadCount) => unreadCount === 0,
      "Trish read receipt",
    );

    const trishMessageId = `${runId}_trish_to_peter_offline`;
    messageIds.push(trishMessageId);
    const trishMessageReference = doc(trishFirestore, "rooms", roomId, "messages", trishMessageId);
    const peterObservedTrishMessage = waitForQueryDocument(
      roomMessages(peterFirestore),
      (snapshot) => snapshot.id === trishMessageId && !snapshot.metadata.hasPendingWrites,
      "Peter realtime receipt of Trish's recovered offline message",
    );
    await disableNetwork(trishFirestore);
    const pendingOfflineMessage = waitForPendingDocument(
      trishMessageReference,
      "Trish local pending message while offline",
    );
    const offlineWrite = setDoc(
      trishMessageReference,
      buildMessagePayload(trishMessageId, trish.uid, `Release acceptance ${runId}: Trish offline to Peter.`),
    );
    await pendingOfflineMessage;
    await enableNetwork(trishFirestore);
    await offlineWrite;
    await Promise.all([peterObservedTrishMessage, waitForNotificationReceipt(adminFirestore, trishMessageId)]);
    await waitForAdminState(
      async () => (await peterMemberReference.get()).get("unreadCount"),
      (unreadCount) => unreadCount === 1,
      "Peter unread count increment",
    );
    await markPeterRead({roomId});
    await waitForAdminState(
      async () => (await peterMemberReference.get()).get("unreadCount"),
      (unreadCount) => unreadCount === 0,
      "Peter read receipt",
    );

    await Promise.all([signOut(peterAuth), signOut(trishAuth)]);
    assert.equal(peterAuth.currentUser, null, "Peter must be locally signed out.");
    assert.equal(trishAuth.currentUser, null, "Trish must be locally signed out.");
    await Promise.all([
      signInAcceptanceClient(peterAuth, peter),
      signInAcceptanceClient(trishAuth, trish),
    ]);
    const [peterRecoveredRoom, trishRecoveredRoom] = await Promise.all([
      getDoc(peterRoomReference),
      getDoc(trishRoomReference),
    ]);
    assert(peterRecoveredRoom.exists() && trishRecoveredRoom.exists(), "Both sessions must recover the room.");
    await Promise.all([signOut(peterAuth), signOut(trishAuth)]);

    await receiptReference.set({
      completedAt: AdminFieldValue.serverTimestamp(),
      gitSha,
      messageIds,
      packageName: PACKAGE_NAME,
      peterUid: provisionedPeter.uid,
      projectId: PROJECT_ID,
      roomId,
      state: "COMPLETE",
      temporaryClientUids: [peter.uid, trish.uid],
      trishUid: provisionedTrish.uid,
      verified: {
        bidirectionalRealtime: true,
        deterministicDirectRoom: true,
        independentAuthentication: true,
        offlineQueueRecovery: true,
        readAndUnreadTransitions: true,
        sessionLogoutAndRecovery: true,
        triggerReceipts: true,
      },
    });
    process.stdout.write(`${JSON.stringify({runId, roomId, messageIds, state: "COMPLETE"})}\n`);
  } catch (error) {
    await receiptReference.set({
      failedAt: AdminFieldValue.serverTimestamp(),
      failure: String(error?.message ?? "Live acceptance failed").split("\n")[0],
      gitSha,
      messageIds,
      packageName: PACKAGE_NAME,
      projectId: PROJECT_ID,
      roomId,
      state: "FAILED",
    });
    throw error;
  } finally {
    await Promise.allSettled(clientFirestores.map((firestore) => terminate(firestore)));
    await Promise.allSettled(clientApps.map((app) => deleteClientApp(app)));
    const cleanupOperations = [];
    if (roomId) {
      cleanupOperations.push(adminFirestore.recursiveDelete(adminFirestore.doc(`rooms/${roomId}`)));
    }
    for (const identity of acceptanceIdentities) {
      cleanupOperations.push(adminFirestore.doc(`profiles/${identity.uid}`).delete());
      cleanupOperations.push(adminAuth.deleteUser(identity.uid));
      identity.password = "";
    }
    const cleanupResults = await Promise.allSettled(cleanupOperations);
    const cleanupFailureCount = cleanupResults.filter((result) => result.status === "rejected").length;
    await receiptReference.set(
      {
        cleanedAt: AdminFieldValue.serverTimestamp(),
        cleanupFailureCount,
        cleanupState: cleanupFailureCount === 0 ? "COMPLETE" : "FAILED",
      },
      {merge: true},
    );
    await deleteAdminApp(adminApp);
    if (cleanupFailureCount > 0) {
      throw new Error(`Live acceptance cleanup failed for ${cleanupFailureCount} operation(s).`);
    }
  }
}

void main().catch((error) => {
  process.stderr.write(`Live acceptance failed: ${String(error?.message ?? "unknown failure").split("\n")[0]}\n`);
  process.exitCode = 1;
});
