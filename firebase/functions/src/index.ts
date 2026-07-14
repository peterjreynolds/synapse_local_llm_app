import {FieldValue, Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {
  buildDirectRoomIdentity,
  buildNotificationReceiptId,
  parseHumanMessagePayload,
  parseTargetUid,
} from "./domain.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
  firebaseAdminMessaging,
} from "./firebaseAdmin.js";
import {selectAuthorizedMessageRecipientUids} from "./recipientAuthorization.js";

export {registerWithInvite} from "./registration.js";
export {
  createInvitation,
  revokeInvitation,
  setRegistrationApprovalRequired,
} from "./invitationAdmin.js";
export {reviewRegistration} from "./registrationReview.js";

const firestore = firebaseAdminFirestore;
const messaging = firebaseAdminMessaging;
const REGION = FIREBASE_FUNCTIONS_REGION;

interface ProfileDocument {
  accountState?: unknown;
  allowed?: unknown;
  displayName?: unknown;
  role?: unknown;
  username?: unknown;
}

interface DeviceDocument {
  active?: unknown;
  installationId?: unknown;
  ownerUid?: unknown;
}

function requireActiveProfile(
  profile: ProfileDocument | undefined,
  uid: string,
): asserts profile is ProfileDocument & {
  accountState: "ACTIVE";
  allowed: true;
  displayName: string;
  role: "OWNER" | "ADMIN" | "USER";
  username: string;
} {
  if (
    profile?.accountState !== "ACTIVE" ||
    profile?.allowed !== true ||
    typeof profile.displayName !== "string" ||
    (profile.role !== "OWNER" && profile.role !== "ADMIN" && profile.role !== "USER") ||
    typeof profile.username !== "string"
  ) {
    throw new HttpsError("permission-denied", `Account ${uid} is not enabled for Synapse Chat.`);
  }
}

export const openDirectRoom = onCall(
  {region: REGION},
  async (request): Promise<{roomId: string}> => {
    const callerUid = request.auth?.uid;
    if (!callerUid) {
      throw new HttpsError("unauthenticated", "Sign in before opening a direct room.");
    }

    let targetUid: string;
    try {
      targetUid = parseTargetUid(request.data);
    } catch (error) {
      throw new HttpsError("invalid-argument", (error as Error).message);
    }
    if (targetUid === callerUid) {
      throw new HttpsError("invalid-argument", "A direct room requires another account.");
    }

    const [callerSnapshot, targetSnapshot] = await Promise.all([
      firestore.doc(`profiles/${callerUid}`).get(),
      firestore.doc(`profiles/${targetUid}`).get(),
    ]);
    const callerProfile = callerSnapshot.data() as ProfileDocument | undefined;
    const targetProfile = targetSnapshot.data() as ProfileDocument | undefined;
    requireActiveProfile(callerProfile, callerUid);
    requireActiveProfile(targetProfile, targetUid);

    const identity = buildDirectRoomIdentity(callerUid, targetUid);
    const roomReference = firestore.doc(`rooms/${identity.roomId}`);

    await firestore.runTransaction(async (transaction) => {
      const roomSnapshot = await transaction.get(roomReference);
      if (roomSnapshot.exists) {
        const existingDirectKey = roomSnapshot.get("directKey");
        const existingKind = roomSnapshot.get("kind");
        if (existingDirectKey !== identity.directKey || existingKind !== "DIRECT") {
          throw new HttpsError("already-exists", "The deterministic room identifier is already occupied.");
        }
        return;
      }

      const createdAt = FieldValue.serverTimestamp();
      transaction.create(roomReference, {
        activeMemberIds: identity.memberIds,
        createdAt,
        directKey: identity.directKey,
        kind: "DIRECT",
        latestMessage: null,
        memberIds: identity.memberIds,
        title: `${callerProfile.displayName}, ${targetProfile.displayName}`,
        updatedAt: createdAt,
      });
      for (const uid of identity.memberIds) {
        transaction.create(roomReference.collection("members").doc(uid), {
          active: true,
          joinedAt: createdAt,
          lastReadAt: null,
          role: "MEMBER",
          uid,
          unreadCount: 0,
        });
      }
    });

    return {roomId: identity.roomId};
  },
);

export const markRoomRead = onCall(
  {region: REGION},
  async (request): Promise<{roomId: string}> => {
    const callerUid = request.auth?.uid;
    if (!callerUid) {
      throw new HttpsError("unauthenticated", "Sign in before marking a room read.");
    }
    const roomId =
      typeof request.data === "object" && request.data !== null && "roomId" in request.data
        ? (request.data as {roomId?: unknown}).roomId
        : null;
    if (typeof roomId !== "string" || !/^direct_[a-f0-9]{64}$/.test(roomId)) {
      throw new HttpsError("invalid-argument", "roomId is invalid.");
    }
    const profileReference = firestore.doc(`profiles/${callerUid}`);
    const membershipReference = firestore.doc(`rooms/${roomId}/members/${callerUid}`);
    const authorizationSnapshots = await firestore.getAll(
      profileReference,
      membershipReference,
    );
    const profileSnapshot = authorizationSnapshots[0];
    const membershipSnapshot = authorizationSnapshots[1];
    if (!profileSnapshot || !membershipSnapshot) {
      throw new HttpsError("internal", "Account authorization could not be resolved.");
    }
    requireActiveProfile(profileSnapshot.data() as ProfileDocument | undefined, callerUid);
    if (!membershipSnapshot.exists || membershipSnapshot.get("active") !== true) {
      throw new HttpsError("permission-denied", "The account is not an active room member.");
    }
    await membershipReference.update({
      lastReadAt: FieldValue.serverTimestamp(),
      unreadCount: 0,
    });
    return {roomId};
  },
);

export const notifyRemoteMessage = onDocumentCreated(
  {
    document: "rooms/{roomId}/messages/{messageId}",
    region: REGION,
    retry: true,
  },
  async (event): Promise<void> => {
    const messageSnapshot = event.data;
    if (!messageSnapshot) {
      return;
    }

    let message;
    try {
      message = parseHumanMessagePayload(messageSnapshot.data());
    } catch {
      return;
    }

    const roomId = event.params.roomId;
    const messageId = event.params.messageId;
    const roomReference = firestore.doc(`rooms/${roomId}`);
    const roomSnapshot = await roomReference.get();
    if (!roomSnapshot.exists) {
      return;
    }
    const memberIds = roomSnapshot.get("memberIds");
    if (!Array.isArray(memberIds) || !memberIds.includes(message.senderUid)) {
      return;
    }
    const candidateRecipientUids = [
      ...new Set(
        memberIds.filter(
          (memberUid): memberUid is string =>
            typeof memberUid === "string" && memberUid !== message.senderUid,
        ),
      ),
    ];
    if (candidateRecipientUids.length === 0) {
      return;
    }
    const authorizationSnapshots = await firestore.getAll(
      ...candidateRecipientUids.flatMap((recipientUid) => [
        firestore.doc(`profiles/${recipientUid}`),
        roomReference.collection("members").doc(recipientUid),
      ]),
    );
    const recipientUids = selectAuthorizedMessageRecipientUids(
      candidateRecipientUids,
      candidateRecipientUids.map((recipientUid, recipientIndex) => ({
        membershipActive: authorizationSnapshots[recipientIndex * 2 + 1]?.get("active") === true,
        profileAllowed:
          authorizationSnapshots[recipientIndex * 2]?.get("allowed") === true &&
          authorizationSnapshots[recipientIndex * 2]?.get("accountState") === "ACTIVE",
        uid: recipientUid,
      })),
    );
    if (recipientUids.length === 0) {
      return;
    }

    const receiptReference = firestore.doc(
      `notificationDeliveries/${buildNotificationReceiptId(event.id)}`,
    );
    try {
      await receiptReference.create({
        eventId: event.id,
        messageId,
        roomId,
        startedAt: FieldValue.serverTimestamp(),
        state: "PROCESSING",
      });
    } catch (error) {
      if ((error as {code?: number}).code === 6) {
        return;
      }
      throw error;
    }

    try {
      const deviceSnapshots = await firestore
        .collection("devices")
        .where("ownerUid", "in", recipientUids)
        .where("active", "==", true)
        .get();
      const deviceRecords = deviceSnapshots.docs
        .map((snapshot) => ({
          reference: snapshot.ref,
          value: snapshot.data() as DeviceDocument,
        }))
        .filter(
          (device): device is typeof device & {value: DeviceDocument & {installationId: string}} =>
            typeof device.value.installationId === "string" &&
            recipientUids.includes(String(device.value.ownerUid)),
        );

      let successCount = 0;
      let failureCount = 0;
      if (deviceRecords.length > 0) {
        const sendResult = await messaging.sendEachForMulticast({
          android: {
            collapseKey: `room_${roomId}`,
            priority: "high",
            ttl: 24 * 60 * 60 * 1000,
          },
          data: {
            messageId,
            roomId,
            senderUid: message.senderUid,
            type: "SYNAPSE_CHAT_MESSAGE",
          },
          fids: deviceRecords.map((device) => device.value.installationId),
        });
        successCount = sendResult.successCount;
        failureCount = sendResult.failureCount;

        const invalidInstallationWrites = firestore.batch();
        sendResult.responses.forEach((response, index) => {
          const errorCode = response.error?.code;
          if (
            errorCode === "messaging/invalid-registration-token" ||
            errorCode === "messaging/registration-token-not-registered"
          ) {
            const deviceRecord = deviceRecords[index];
            if (deviceRecord) {
              invalidInstallationWrites.update(deviceRecord.reference, {
                active: false,
                disabledAt: FieldValue.serverTimestamp(),
              });
            }
          }
        });
        await invalidInstallationWrites.commit();
      }

      const updatedAt = Timestamp.now();
      const summaryWrites = firestore.batch();
      summaryWrites.update(roomReference, {
        latestMessage: {
          body: message.body,
          messageId,
          senderUid: message.senderUid,
        },
        updatedAt,
      });
      for (const recipientUid of recipientUids) {
        summaryWrites.update(roomReference.collection("members").doc(recipientUid), {
          unreadCount: FieldValue.increment(1),
        });
      }
      summaryWrites.update(receiptReference, {
        completedAt: updatedAt,
        failureCount,
        state: "COMPLETE",
        successCount,
      });
      await summaryWrites.commit();
    } catch (error) {
      await receiptReference.delete();
      throw error;
    }
  },
);
