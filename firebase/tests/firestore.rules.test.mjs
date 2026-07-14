import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  collection,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
} from "firebase/firestore";

const PROJECT_ID = "demo-synapse-chat";
const PETER_UID = "peter-uid";
const TRISH_UID = "trish-uid";
const MALLORY_UID = "mallory-uid";
const PENDING_UID = "pending-uid";
const ROOM_ID = `direct_${"a".repeat(64)}`;
const firestoreRules = fs.readFileSync(new URL("../firestore.rules", import.meta.url), "utf8");

let testEnvironment;

function profile(uid, username, accountState = "ACTIVE") {
  const normalized = username.toLowerCase();
  const allowed = accountState === "ACTIVE";
  return {
    accountState,
    allowed,
    avatarUrl: null,
    bio: "",
    createdAt: Timestamp.fromMillis(1),
    directoryVisible: true,
    displayName: username,
    lastSeenAt: null,
    mustChangePassword: false,
    online: false,
    role: "USER",
    updatedAt: Timestamp.fromMillis(1),
    username,
    usernameNormalized: normalized,
  };
}

async function seedChat() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    await setDoc(doc(firestore, "profiles", PETER_UID), profile(PETER_UID, "Peter"));
    await setDoc(doc(firestore, "profiles", TRISH_UID), profile(TRISH_UID, "Trish"));
    await setDoc(doc(firestore, "profiles", MALLORY_UID), profile(MALLORY_UID, "Mallory", "DISABLED"));
    await setDoc(doc(firestore, "profiles", PENDING_UID), profile(PENDING_UID, "Pending", "PENDING_APPROVAL"));
    await setDoc(doc(firestore, "rooms", ROOM_ID), {
      activeMemberIds: [PETER_UID, TRISH_UID],
      createdAt: Timestamp.fromMillis(1),
      directKey: `${PETER_UID}:${TRISH_UID}`,
      kind: "DIRECT",
      latestMessage: null,
      memberIds: [PETER_UID, TRISH_UID],
      title: "Peter, Trish",
      updatedAt: Timestamp.fromMillis(1),
    });
    for (const uid of [PETER_UID, TRISH_UID]) {
      await setDoc(doc(firestore, "rooms", ROOM_ID, "members", uid), {
        active: true,
        joinedAt: Timestamp.fromMillis(1),
        lastReadAt: null,
        role: "MEMBER",
        uid,
        unreadCount: 0,
      });
    }
  });
}

function activeContext(uid, role = "USER") {
  return testEnvironment.authenticatedContext(uid, {
    accountState: "ACTIVE",
    mustChangePassword: false,
    role,
  });
}

function pendingContext(uid) {
  return testEnvironment.authenticatedContext(uid, {
    accountState: "PENDING_APPROVAL",
    mustChangePassword: false,
    role: "USER",
  });
}

function validMessage(senderUid, messageId, body = "Hello") {
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

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    firestore: {rules: firestoreRules},
    projectId: PROJECT_ID,
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
  await seedChat();
});

after(async () => {
  await testEnvironment.cleanup();
});

test("requires authentication and allowlist membership for the directory", async () => {
  const unauthenticated = testEnvironment.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(unauthenticated, "profiles", PETER_UID)));

  const peter = activeContext(PETER_UID).firestore();
  const directoryQuery = query(
    collection(peter, "profiles"),
    where("allowed", "==", true),
    where("accountState", "==", "ACTIVE"),
    where("directoryVisible", "==", true),
    orderBy("usernameNormalized"),
  );
  const directory = await assertSucceeds(getDocs(directoryQuery));
  const usernames = directory.docs.map((snapshot) => snapshot.get("username"));
  usernames.sort();
  if (usernames.join(",") !== "Peter,Trish") {
    throw new Error(`Unexpected directory contents: ${usernames.join(",")}`);
  }
});

test("limits pending accounts to their own status profile", async () => {
  const pending = pendingContext(PENDING_UID).firestore();
  await assertSucceeds(getDoc(doc(pending, "profiles", PENDING_UID)));
  await assertFails(getDoc(doc(pending, "profiles", PETER_UID)));
  await assertFails(getDocs(collection(pending, "profiles")));
  await assertFails(getDoc(doc(pending, "rooms", ROOM_ID)));
  await assertFails(
    setDoc(doc(pending, "devices", "pending-device"), {
      active: true,
      createdAt: serverTimestamp(),
      installationId: "pending-firebase-installation-id",
      ownerUid: PENDING_UID,
      platform: "ANDROID",
      updatedAt: serverTimestamp(),
    }),
  );
});

test("denies active-looking tokens when profile state is disabled", async () => {
  const mallory = activeContext(MALLORY_UID).firestore();
  await assertSucceeds(getDoc(doc(mallory, "profiles", MALLORY_UID)));
  await assertFails(getDocs(collection(mallory, "profiles")));
  await assertFails(getDoc(doc(mallory, "rooms", ROOM_ID)));
});

test("limits forced-password-change accounts to their own profile", async () => {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "profiles", PETER_UID), {
      mustChangePassword: true,
    });
  });
  const peter = testEnvironment.authenticatedContext(PETER_UID, {
    accountState: "ACTIVE",
    mustChangePassword: true,
    role: "USER",
  }).firestore();
  await assertSucceeds(getDoc(doc(peter, "profiles", PETER_UID)));
  await assertFails(getDocs(collection(peter, "profiles")));
  await assertFails(getDoc(doc(peter, "rooms", ROOM_ID)));
});

test("permits presentation-only self profile updates and rejects identity changes", async () => {
  const peter = activeContext(PETER_UID).firestore();
  const peterProfile = doc(peter, "profiles", PETER_UID);
  await assertSucceeds(
    updateDoc(peterProfile, {
      bio: "Local-first builder",
      displayName: "Peter R.",
      lastSeenAt: serverTimestamp(),
      online: true,
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(updateDoc(peterProfile, {allowed: false}));
  await assertFails(updateDoc(peterProfile, {username: "Trish"}));

  const trish = activeContext(TRISH_UID).firestore();
  await assertFails(updateDoc(doc(trish, "profiles", PETER_UID), {displayName: "Forged"}));
});

test("denies room and message data to non-members", async () => {
  const mallory = activeContext(MALLORY_UID).firestore();
  await assertFails(getDoc(doc(mallory, "rooms", ROOM_ID)));
  await assertFails(getDocs(collection(mallory, "rooms", ROOM_ID, "messages")));

  const peter = activeContext(PETER_UID).firestore();
  await assertSucceeds(getDoc(doc(peter, "rooms", ROOM_ID)));
  await assertSucceeds(
    getDocs(
      query(
        collection(peter, "rooms"),
        where("activeMemberIds", "array-contains", PETER_UID),
      ),
    ),
  );
  await assertSucceeds(getDocs(collection(peter, "rooms", ROOM_ID, "messages")));
});

test("denies room summaries after membership becomes inactive", async () => {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "rooms", ROOM_ID, "members", TRISH_UID), {
      active: false,
    });
    await updateDoc(doc(context.firestore(), "rooms", ROOM_ID), {
      activeMemberIds: [PETER_UID],
    });
  });
  const trish = activeContext(TRISH_UID).firestore();
  await assertFails(getDoc(doc(trish, "rooms", ROOM_ID)));
  await assertFails(getDocs(collection(trish, "rooms", ROOM_ID, "messages")));
});

test("accepts idempotent member messages and rejects forged senders", async () => {
  const peter = activeContext(PETER_UID).firestore();
  const messageId = `${PETER_UID}_client-message-1`;
  await assertSucceeds(
    setDoc(doc(peter, "rooms", ROOM_ID, "messages", messageId), validMessage(PETER_UID, messageId)),
  );

  const forgedMessageId = `${PETER_UID}_forged-message`;
  await assertFails(
    setDoc(
      doc(peter, "rooms", ROOM_ID, "messages", forgedMessageId),
      validMessage(TRISH_UID, forgedMessageId),
    ),
  );
});

test("rejects invalid messages and all client membership writes", async () => {
  const peter = activeContext(PETER_UID).firestore();
  const blankMessageId = `${PETER_UID}_blank-message`;
  await assertFails(
    setDoc(doc(peter, "rooms", ROOM_ID, "messages", blankMessageId), validMessage(PETER_UID, blankMessageId, "")),
  );
  const longMessageId = `${PETER_UID}_long-message`;
  await assertFails(
    setDoc(
      doc(peter, "rooms", ROOM_ID, "messages", longMessageId),
      validMessage(PETER_UID, longMessageId, "x".repeat(4001)),
    ),
  );
  await assertFails(
    updateDoc(doc(peter, "rooms", ROOM_ID, "members", PETER_UID), {unreadCount: 0}),
  );
});

test("keeps FCM installation IDs server-owned", async () => {
  const peter = activeContext(PETER_UID).firestore();
  const peterDevice = doc(peter, "devices", "peter-device");
  await assertFails(
    setDoc(peterDevice, {
      active: true,
      createdAt: serverTimestamp(),
      installationId: "peter-firebase-installation-id",
      ownerUid: PETER_UID,
      platform: "ANDROID",
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(getDoc(peterDevice));
  const notificationPreferences = doc(peter, "notificationPreferences", PETER_UID);
  await assertFails(getDoc(notificationPreferences));
  await assertFails(setDoc(notificationPreferences, {
    directMessages: true,
    groupMessages: true,
    mentions: true,
    mutedRooms: false,
  }));
});

test("keeps AI participant, configuration, queue, and audit state server-owned", async () => {
  const peterDb = activeContext(PETER_UID).firestore();
  await assertFails(setDoc(doc(peterDb, "callableRateLimits", "forged"), {requestCount: 1}));
  await assertFails(setDoc(doc(peterDb, "operationsJobStatus", "forged"), {state: "SUCCEEDED"}));
  await assertFails(setDoc(doc(peterDb, "roomAiConfigurations", ROOM_ID), {localAiEnabled: true}));
  await assertFails(setDoc(doc(peterDb, "localAiHostQueues", "device", "jobs", "job"), {state: "PENDING"}));
  await assertFails(setDoc(doc(peterDb, "remoteAiAuditEvents", "event"), {eventType: "forged"}));
  await assertFails(setDoc(doc(peterDb, "remoteAiResponseAudits", "job"), {completionState: "COMPLETE"}));
  await assertFails(setDoc(
    doc(peterDb, "rooms", ROOM_ID, "participants", "participant-synapse-local-ai"),
    {active: true},
  ));
});

test("keeps block and account deletion state server-owned", async () => {
  const peter = activeContext(PETER_UID).firestore();
  await assertFails(getDoc(doc(peter, "blocks", "private-block")));
  await assertFails(
    setDoc(doc(peter, "blocks", "private-block"), {
      blockedUid: TRISH_UID,
      blockerUid: PETER_UID,
      createdAt: serverTimestamp(),
    }),
  );
  await assertFails(getDoc(doc(peter, "accountDeletionRequests", PETER_UID)));
  await assertFails(
    setDoc(doc(peter, "accountDeletionRequests", PETER_UID), {
      requestedAt: serverTimestamp(),
      requestedBy: PETER_UID,
      state: "PENDING",
    }),
  );
});
