import {randomUUID} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {logger} from "firebase-functions";
import {
  assertActiveAccountProfile,
  requireActiveAccount,
} from "./accountAuthorization.js";
import {enforceCallableRateLimit} from "./callableRateLimit.js";
import {
  buildDirectCallNotificationData,
  DIRECT_CALL_RINGING_TIMEOUT_MILLIS,
  DirectCallMediaKind,
  DirectCallSignalCommand,
  isDirectCallPointerBusy,
  parseDirectCallId,
  parseDirectCallResponseCommand,
  parseDirectCallSignalCommand,
  parseStartDirectCallCommand,
} from "./directCallDomain.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
  firebaseAdminMessaging,
} from "./firebaseAdmin.js";
import {buildReciprocalBlockReferences} from "./privacyAdmin.js";

const ACTIVE_CALL_TIMEOUT_MILLIS = 8 * 60 * 60_000;

type DirectCallState = "ACTIVE" | "CANCELED" | "DECLINED" | "ENDED" | "MISSED" | "RINGING";

interface DirectCallReceipt {
  callId: string;
  callerUid: string;
  calleeUid: string;
  expiresAtMillis: number;
  mediaKind: DirectCallMediaKind;
  roomId: string;
  state: DirectCallState;
}

interface DeviceDocument {
  active?: unknown;
  installationId?: unknown;
  ownerUid?: unknown;
}

export const startDirectCall = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<DirectCallReceipt> => {
    const {uid: callerUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(callerUid, "callMutation");
    const {mediaKind, roomId} = parseCommand(() => parseStartDirectCallCommand(request.data));
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const initialRoom = await roomReference.get();
    const calleeUid = readDirectCallPeerUid(initialRoom.data(), callerUid);
    const callId = `call_${randomUUID().replaceAll("-", "")}`;
    const callReference = firebaseAdminFirestore.doc(`callSessions/${callId}`);
    const callerPointer = firebaseAdminFirestore.doc(`activeCallPointers/${callerUid}`);
    const calleePointer = firebaseAdminFirestore.doc(`activeCallPointers/${calleeUid}`);
    const callerMembership = roomReference.collection("members").doc(callerUid);
    const calleeMembership = roomReference.collection("members").doc(calleeUid);
    const calleeProfile = firebaseAdminFirestore.doc(`profiles/${calleeUid}`);
    const blockReferences = buildReciprocalBlockReferences(callerUid, calleeUid);
    const now = Timestamp.now();
    const expiresAt = Timestamp.fromMillis(now.toMillis() + DIRECT_CALL_RINGING_TIMEOUT_MILLIS);

    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [
        room,
        callerMember,
        calleeMember,
        calleeAccount,
        callerBlock,
        calleeBlock,
        callerActiveCall,
        calleeActiveCall,
      ] = await Promise.all([
        transaction.get(roomReference),
        transaction.get(callerMembership),
        transaction.get(calleeMembership),
        transaction.get(calleeProfile),
        transaction.get(blockReferences[0]),
        transaction.get(blockReferences[1]),
        transaction.get(callerPointer),
        transaction.get(calleePointer),
      ]);
      if (readDirectCallPeerUid(room.data(), callerUid) !== calleeUid) {
        throw new HttpsError("failed-precondition", "The direct conversation changed. Try again.");
      }
      if (callerMember.get("active") !== true || calleeMember.get("active") !== true) {
        throw new HttpsError("permission-denied", "The direct conversation is unavailable.");
      }
      assertActiveAccountProfile(calleeAccount.data());
      if (callerBlock.exists || calleeBlock.exists) {
        throw new HttpsError("permission-denied", "The direct conversation is unavailable.");
      }
      if (
        isDirectCallPointerBusy(readPointer(callerActiveCall), now.toMillis()) ||
        isDirectCallPointerBusy(readPointer(calleeActiveCall), now.toMillis())
      ) {
        throw new HttpsError("failed-precondition", "One of you is already in another Synapse call.");
      }
      transaction.create(callReference, {
        acceptedAt: null,
        calleeUid,
        callerUid,
        createdAt: now,
        endedAt: null,
        endedByUid: null,
        expiresAt,
        mediaKind,
        roomId,
        state: "RINGING",
      });
      transaction.set(callerPointer, buildActiveCallPointer(callId, calleeUid, roomId, "CALLER", expiresAt, now));
      transaction.set(calleePointer, buildActiveCallPointer(callId, callerUid, roomId, "CALLEE", expiresAt, now));
    });

    await sendNotificationWithoutInvalidatingMutation(calleeUid, buildDirectCallNotificationData({
      callId,
      event: "INCOMING",
      expiresAtMillis: expiresAt.toMillis(),
      mediaKind,
    }));
    return {
      callId,
      calleeUid,
      callerUid,
      expiresAtMillis: expiresAt.toMillis(),
      mediaKind,
      roomId,
      state: "RINGING",
    };
  },
);

export const respondDirectCall = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<DirectCallReceipt> => {
    const {uid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(uid, "callMutation");
    const command = parseCommand(() => parseDirectCallResponseCommand(request.data));
    const callReference = firebaseAdminFirestore.doc(`callSessions/${command.callId}`);
    let receipt: DirectCallReceipt | null = null;
    let notificationRecipientUids: string[] = [];
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      notificationRecipientUids = [];
      const call = await transaction.get(callReference);
      const session = readDirectCallSession(call.data(), command.callId);
      if (session.calleeUid !== uid) {
        throw new HttpsError("permission-denied", "Only the called account can answer this call.");
      }
      const callerPointer = firebaseAdminFirestore.doc(`activeCallPointers/${session.callerUid}`);
      const calleePointer = firebaseAdminFirestore.doc(`activeCallPointers/${session.calleeUid}`);
      const pointerSnapshots = await Promise.all([
        transaction.get(callerPointer),
        transaction.get(calleePointer),
      ]);
      const now = Timestamp.now();
      if (session.state === "ACTIVE" && command.action === "ACCEPT") {
        receipt = session;
        return;
      }
      if (isTerminalDirectCallState(session.state)) {
        receipt = session;
        return;
      }
      if (session.state !== "RINGING") {
        throw new HttpsError("failed-precondition", "This call is no longer ringing.");
      }
      if (session.expiresAtMillis <= now.toMillis()) {
        transaction.update(callReference, {endedAt: now, endedByUid: null, state: "MISSED"});
        clearMatchingPointers(transaction, pointerSnapshots, command.callId);
        receipt = {...session, state: "MISSED"};
        notificationRecipientUids = [session.callerUid, session.calleeUid];
        return;
      }
      if (pointerSnapshots.some((pointer) => !pointer.exists || pointer.get("callId") !== command.callId)) {
        throw new HttpsError("failed-precondition", "This call is no longer active.");
      }
      if (command.action === "DECLINE") {
        transaction.update(callReference, {endedAt: now, endedByUid: uid, state: "DECLINED"});
        clearMatchingPointers(transaction, pointerSnapshots, command.callId);
        receipt = {...session, state: "DECLINED"};
        notificationRecipientUids = [session.callerUid, session.calleeUid];
        return;
      }
      const activeUntil = Timestamp.fromMillis(now.toMillis() + ACTIVE_CALL_TIMEOUT_MILLIS);
      transaction.update(callReference, {acceptedAt: now, expiresAt: activeUntil, state: "ACTIVE"});
      transaction.update(callerPointer, {expiresAt: activeUntil, updatedAt: now});
      transaction.update(calleePointer, {expiresAt: activeUntil, updatedAt: now});
      receipt = {...session, expiresAtMillis: activeUntil.toMillis(), state: "ACTIVE"};
    });
    const completed = requireReceipt(receipt);
    await notifyDirectCallTerminated(notificationRecipientUids, completed);
    return completed;
  },
);

export const endDirectCall = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<DirectCallReceipt> => {
    const {uid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(uid, "callMutation");
    const callId = parseCommand(() => parseDirectCallId(request.data));
    const callReference = firebaseAdminFirestore.doc(`callSessions/${callId}`);
    let receipt: DirectCallReceipt | null = null;
    let notificationRecipientUids: string[] = [];
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      notificationRecipientUids = [];
      const call = await transaction.get(callReference);
      const session = readDirectCallSession(call.data(), callId);
      if (session.callerUid !== uid && session.calleeUid !== uid) {
        throw new HttpsError("permission-denied", "This call is unavailable.");
      }
      if (isTerminalDirectCallState(session.state)) {
        receipt = session;
        return;
      }
      const callerPointer = firebaseAdminFirestore.doc(`activeCallPointers/${session.callerUid}`);
      const calleePointer = firebaseAdminFirestore.doc(`activeCallPointers/${session.calleeUid}`);
      const pointerSnapshots = await Promise.all([
        transaction.get(callerPointer),
        transaction.get(calleePointer),
      ]);
      const now = Timestamp.now();
      const terminalState: DirectCallState = session.state === "ACTIVE" ? "ENDED" :
        session.expiresAtMillis <= now.toMillis() ? "MISSED" :
          uid === session.callerUid ? "CANCELED" : "DECLINED";
      transaction.update(callReference, {
        endedAt: now,
        endedByUid: terminalState === "MISSED" ? null : uid,
        state: terminalState,
      });
      clearMatchingPointers(transaction, pointerSnapshots, callId);
      receipt = {...session, state: terminalState};
      notificationRecipientUids = [session.callerUid, session.calleeUid];
    });
    const completed = requireReceipt(receipt);
    await notifyDirectCallTerminated(notificationRecipientUids, completed);
    return completed;
  },
);

export const expireDirectCall = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<DirectCallReceipt> => {
    const {uid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(uid, "callMutation");
    const callId = parseCommand(() => parseDirectCallId(request.data));
    const callReference = firebaseAdminFirestore.doc(`callSessions/${callId}`);
    let receipt: DirectCallReceipt | null = null;
    let notificationRecipientUids: string[] = [];
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      notificationRecipientUids = [];
      const call = await transaction.get(callReference);
      const session = readDirectCallSession(call.data(), callId);
      if (session.callerUid !== uid && session.calleeUid !== uid) {
        throw new HttpsError("permission-denied", "This call is unavailable.");
      }
      if (session.state !== "RINGING") {
        receipt = session;
        return;
      }
      const now = Timestamp.now();
      if (session.expiresAtMillis > now.toMillis()) {
        receipt = session;
        return;
      }
      const callerPointer = firebaseAdminFirestore.doc(`activeCallPointers/${session.callerUid}`);
      const calleePointer = firebaseAdminFirestore.doc(`activeCallPointers/${session.calleeUid}`);
      const pointerSnapshots = await Promise.all([
        transaction.get(callerPointer),
        transaction.get(calleePointer),
      ]);
      transaction.update(callReference, {endedAt: now, endedByUid: null, state: "MISSED"});
      clearMatchingPointers(transaction, pointerSnapshots, callId);
      receipt = {...session, state: "MISSED"};
      notificationRecipientUids = [session.callerUid, session.calleeUid];
    });
    const completed = requireReceipt(receipt);
    await notifyDirectCallTerminated(notificationRecipientUids, completed);
    return completed;
  },
);

export const publishDirectCallSignal = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{callId: string; signalId: string}> => {
    const {uid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(uid, "callSignaling");
    const command = parseCommand(() => parseDirectCallSignalCommand(request.data));
    const callReference = firebaseAdminFirestore.doc(`callSessions/${command.callId}`);
    const signalReference = callReference.collection("signals").doc(command.signalId);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [call, existingSignal] = await Promise.all([
        transaction.get(callReference),
        transaction.get(signalReference),
      ]);
      const session = readDirectCallSession(call.data(), command.callId);
      if (session.state !== "ACTIVE" || session.expiresAtMillis <= Date.now()) {
        throw new HttpsError("failed-precondition", "This call is no longer active.");
      }
      if (session.callerUid !== uid && session.calleeUid !== uid) {
        throw new HttpsError("permission-denied", "This call is unavailable.");
      }
      authorizeSignalRole(command, uid, session);
      if (existingSignal.exists) {
        if (signalMatchesDocument(existingSignal, command, uid)) return;
        throw new HttpsError("already-exists", "The call signal identifier is already in use.");
      }
      transaction.create(signalReference, {
        ...serializeSignal(command),
        createdAt: Timestamp.now(),
        senderUid: uid,
      });
    });
    return {callId: command.callId, signalId: command.signalId};
  },
);

function readDirectCallPeerUid(room: FirebaseFirestore.DocumentData | undefined, callerUid: string): string {
  const memberIds = room?.memberIds;
  if (
    room?.kind !== "DIRECT" ||
    !Array.isArray(memberIds) ||
    memberIds.length !== 2 ||
    !memberIds.every((uid): uid is string => typeof uid === "string") ||
    !memberIds.includes(callerUid)
  ) {
    throw new HttpsError("permission-denied", "The direct conversation is unavailable.");
  }
  const peerUid = memberIds.find((uid) => uid !== callerUid);
  if (peerUid === undefined) {
    throw new HttpsError("permission-denied", "The direct conversation is unavailable.");
  }
  return peerUid;
}

function buildActiveCallPointer(
  callId: string,
  peerUid: string,
  roomId: string,
  role: "CALLEE" | "CALLER",
  expiresAt: Timestamp,
  updatedAt: Timestamp,
) {
  return {callId, expiresAt, peerUid, role, roomId, updatedAt};
}

function readPointer(snapshot: FirebaseFirestore.DocumentSnapshot): {
  exists: boolean;
  expiresAtMillis: number | null;
} {
  const expiresAt = snapshot.get("expiresAt");
  return {
    exists: snapshot.exists,
    expiresAtMillis: expiresAt instanceof Timestamp ? expiresAt.toMillis() : null,
  };
}

function readDirectCallSession(input: unknown, callId: string): DirectCallReceipt {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new HttpsError("not-found", "The call was not found.");
  }
  const session = input as Record<string, unknown>;
  const expiresAt = session.expiresAt;
  const mediaKind = readDirectCallMediaKind(session.mediaKind);
  if (
    typeof session.callerUid !== "string" ||
    typeof session.calleeUid !== "string" ||
    typeof session.roomId !== "string" ||
    !/^direct_[a-f0-9]{64}$/.test(session.roomId) ||
    !(expiresAt instanceof Timestamp) ||
    (session.state !== "ACTIVE" &&
      session.state !== "CANCELED" &&
      session.state !== "DECLINED" &&
      session.state !== "ENDED" &&
      session.state !== "MISSED" &&
      session.state !== "RINGING")
  ) {
    throw new HttpsError("data-loss", "The call record is malformed.");
  }
  return {
    callId,
    calleeUid: session.calleeUid,
    callerUid: session.callerUid,
    expiresAtMillis: expiresAt.toMillis(),
    mediaKind,
    roomId: session.roomId,
    state: session.state,
  };
}

function readDirectCallMediaKind(input: unknown): DirectCallMediaKind {
  if (input === undefined) return "AUDIO";
  if (input === "AUDIO" || input === "VIDEO") return input;
  throw new HttpsError("data-loss", "The call record is malformed.");
}

function clearMatchingPointers(
  transaction: FirebaseFirestore.Transaction,
  pointerSnapshots: FirebaseFirestore.DocumentSnapshot[],
  callId: string,
) {
  pointerSnapshots.forEach((pointer) => {
    if (pointer.exists && pointer.get("callId") === callId) transaction.delete(pointer.ref);
  });
}

function authorizeSignalRole(
  signal: DirectCallSignalCommand,
  senderUid: string,
  session: DirectCallReceipt,
) {
  if (signal.kind === "OFFER" && senderUid !== session.callerUid) {
    throw new HttpsError("permission-denied", "Only the caller can publish the offer.");
  }
  if (signal.kind === "ANSWER" && senderUid !== session.calleeUid) {
    throw new HttpsError("permission-denied", "Only the called account can publish the answer.");
  }
}

function serializeSignal(command: DirectCallSignalCommand): Record<string, unknown> {
  return command.kind === "ICE" ? {
    candidate: command.candidate,
    kind: command.kind,
    sdpMid: command.sdpMid,
    sdpMLineIndex: command.sdpMLineIndex,
  } : {
    kind: command.kind,
    sdp: command.sdp,
  };
}

function signalMatchesDocument(
  snapshot: FirebaseFirestore.DocumentSnapshot,
  command: DirectCallSignalCommand,
  senderUid: string,
): boolean {
  if (snapshot.get("senderUid") !== senderUid || snapshot.get("kind") !== command.kind) return false;
  if (command.kind === "OFFER" || command.kind === "ANSWER") {
    return snapshot.get("sdp") === command.sdp;
  }
  if (command.kind === "ICE") {
    return snapshot.get("candidate") === command.candidate &&
      snapshot.get("sdpMid") === command.sdpMid &&
      snapshot.get("sdpMLineIndex") === command.sdpMLineIndex;
  }
  return false;
}

async function sendDirectCallNotification(recipientUid: string, data: Record<string, string>): Promise<void> {
  const devices = await firebaseAdminFirestore.collection("devices")
    .where("ownerUid", "==", recipientUid)
    .where("active", "==", true)
    .get();
  const records = devices.docs
    .map((snapshot) => ({reference: snapshot.ref, value: snapshot.data() as DeviceDocument}))
    .filter((device): device is typeof device & {value: DeviceDocument & {installationId: string}} =>
      typeof device.value.installationId === "string" && device.value.ownerUid === recipientUid,
    );
  if (records.length === 0) return;
  const result = await firebaseAdminMessaging.sendEach(records.map((device) => ({
    android: {
      collapseKey: `call_${data.callId ?? "synapse"}`,
      priority: "high" as const,
      ttl: DIRECT_CALL_RINGING_TIMEOUT_MILLIS,
    },
    data,
    fid: device.value.installationId,
  })));
  const batch = firebaseAdminFirestore.batch();
  result.responses.forEach((response, index) => {
    const errorCode = response.error?.code;
    if (
      errorCode === "messaging/invalid-registration-token" ||
      errorCode === "messaging/registration-token-not-registered"
    ) {
      const device = records[index];
      if (device) batch.update(device.reference, {active: false, disabledAt: Timestamp.now()});
    }
  });
  await batch.commit();
}

async function notifyDirectCallTerminated(
  recipientUids: readonly string[],
  session: DirectCallReceipt,
): Promise<void> {
  const notificationData = buildDirectCallNotificationData({
    callId: session.callId,
    event: "ENDED",
    expiresAtMillis: session.expiresAtMillis,
    mediaKind: session.mediaKind,
  });
  await Promise.all(
    [...new Set(recipientUids)].map((recipientUid) =>
      sendNotificationWithoutInvalidatingMutation(recipientUid, notificationData),
    ),
  );
}

function isTerminalDirectCallState(state: DirectCallState): boolean {
  return state === "CANCELED" || state === "DECLINED" || state === "ENDED" || state === "MISSED";
}

async function sendNotificationWithoutInvalidatingMutation(
  recipientUid: string,
  data: Record<string, string>,
): Promise<void> {
  try {
    await sendDirectCallNotification(recipientUid, data);
  } catch (error) {
    logger.error("Direct-call notification delivery failed after a committed call mutation.", {
      callId: data.callId,
      errorCode: typeof error === "object" && error !== null && "code" in error ? String(error.code) : "unknown",
    });
  }
}

function parseCommand<T>(parser: () => T): T {
  try {
    return parser();
  } catch (error) {
    if (error instanceof HttpsError) throw error;
    throw new HttpsError("invalid-argument", error instanceof Error ? error.message : "Call command is invalid.");
  }
}

function requireReceipt(receipt: DirectCallReceipt | null): DirectCallReceipt {
  if (receipt === null) throw new HttpsError("internal", "The call mutation did not produce a receipt.");
  return receipt;
}
