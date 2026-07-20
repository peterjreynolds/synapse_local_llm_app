import {FieldValue, Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {
  buildDirectRoomIdentity,
  buildNotificationReceiptId,
  parseRemoteNotificationMessagePayload,
  parseTargetUid,
} from "./domain.js";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {sendMetadataOnlyMessageNotification} from "./messageNotificationDelivery.js";
import {selectAuthorizedMessageRecipientUids} from "./recipientAuthorization.js";
import {
  nextRemoteNotificationUnreadCount,
  readNotificationPreferences,
  shouldNotifyForRemoteMessage,
} from "./notificationPreferenceDomain.js";
import {isRoomMuteActive} from "./roomPreferenceDomain.js";
import {requireActiveAccount} from "./accountAuthorization.js";
import {
  buildReciprocalBlockReferences,
} from "./privacyAdmin.js";
import {enforceCallableRateLimit} from "./callableRateLimit.js";

export {registerWithInvite} from "./registration.js";
export {
  createInvitation,
  getOwnerRegistrationConfiguration,
  listOwnerInvitations,
  revokeInvitation,
  setRegistrationApprovalRequired,
} from "./invitationAdmin.js";
export {reviewRegistration} from "./registrationReview.js";
export {listOwnerAccounts} from "./ownerAccountDirectory.js";
export {
  createAccountForUser,
  deleteOwnerAccount,
  revokeOwnerAccountSessions,
  setOwnerAccountEnabled,
} from "./ownerAccountMutation.js";
export {
  listOwnerDevices,
  removeOwnerDevice,
  sendOwnerTestPush,
} from "./ownerDeviceAdmin.js";
export {listOwnerAuditEvents} from "./ownerAuditAdmin.js";
export {
  completeRequiredPasswordChange,
  resetOwnerAccountPassword,
} from "./passwordAdmin.js";
export {
  cancelAccountDeletionRequest,
  getOwnPrivacyState,
  listOwnDevices,
  registerOwnDevice,
  removeOwnDevice,
  requestAccountDeletion,
  setUserBlocked,
} from "./privacyAdmin.js";
export {
  addGroupMembers,
  createGroupRoom,
  deleteGroupRoom,
  leaveGroupRoom,
  removeGroupMember,
  renameGroupRoom,
  setGroupAvatar,
  setGroupMemberRole,
  transferGroupOwnership,
  updateGroupPreferences,
} from "./groupRoomMutation.js";
export {getGroupRoomDetails} from "./groupRoomQuery.js";
export {updateRoomPreferences} from "./roomPreferenceMutation.js";
export {
  getNotificationPreferences,
  updateNotificationPreferences,
} from "./notificationPreferenceMutation.js";
export {
  cleanupDeletedMessageAttachments,
  cleanupExpiredAttachmentUploads,
} from "./attachmentCleanup.js";
export {cleanupExpiredOperationalData} from "./operationalDataCleanup.js";
export {getOwnerOperationsSummary} from "./ownerOperations.js";
export {
  cancelRemoteAttachment,
  finalizeRemoteAttachment,
  prepareRemoteAttachment,
} from "./attachmentMutation.js";
export {
  acknowledgeRemoteMessages,
  deleteRemoteMessage,
  editRemoteMessage,
  sendRemoteMessage,
  toggleRemoteReaction,
} from "./richMessageMutation.js";
export {
  getRoomAiConfiguration,
  heartbeatLocalAiHost,
  updateRoomAiConfiguration,
} from "./remoteAiConfiguration.js";
export {
  claimNextLocalAiResponse,
  completeLocalAiResponse,
  failLocalAiResponse,
  skipLocalAiResponse,
} from "./remoteAiLease.js";
export {queueRemoteLocalAiResponse} from "./remoteAiQueue.js";
export {
  getCinderAvailability,
  submitCinderMessage,
  syncCinderMessages,
} from "./cinderConversation.js";
export {
  getCinderParticipant,
  listCinderParticipants,
  setCinderParticipant,
} from "./cinderParticipant.js";
export {notifyCinderMessage} from "./cinderNotification.js";
export {queueCinderHumanRoomResponse} from "./cinderQueue.js";
export {
  claimCinderResponse,
  completeCinderResponseJob,
  failCinderResponseJob,
  sendCinderOutboundMessage,
  skipCinderResponseJob,
} from "./cinderWorker.js";
export {
  endDirectCall,
  expireDirectCall,
  publishDirectCallSignal,
  respondDirectCall,
  startDirectCall,
} from "./directCallMutation.js";

const firestore = firebaseAdminFirestore;
const REGION = FIREBASE_FUNCTIONS_REGION;

interface ProfileDocument {
  accountState?: unknown;
  allowed?: unknown;
  displayName?: unknown;
  mustChangePassword?: unknown;
  role?: unknown;
  username?: unknown;
}

function requireActiveProfile(
  profile: ProfileDocument | undefined,
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
    profile.mustChangePassword !== false ||
    (profile.role !== "OWNER" && profile.role !== "ADMIN" && profile.role !== "USER") ||
    typeof profile.username !== "string"
  ) {
    throw new HttpsError("permission-denied", "The selected account is unavailable.");
  }
}

export const openDirectRoom = onCall(
  {region: REGION},
  async (request): Promise<{roomId: string}> => {
    const callerUid = (await requireActiveAccount(request.auth)).uid;
    await enforceCallableRateLimit(callerUid, "conversationMutation");

    let targetUid: string;
    try {
      targetUid = parseTargetUid(request.data);
    } catch {
      throw new HttpsError("invalid-argument", "The selected account is invalid.");
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
    requireActiveProfile(callerProfile);
    requireActiveProfile(targetProfile);

    const identity = buildDirectRoomIdentity(callerUid, targetUid);
    const roomReference = firestore.doc(`rooms/${identity.roomId}`);
    const blockReferences = buildReciprocalBlockReferences(callerUid, targetUid);

    await firestore.runTransaction(async (transaction) => {
      const [roomSnapshot, callerBlock, targetBlock] = await Promise.all([
        transaction.get(roomReference),
        transaction.get(blockReferences[0]),
        transaction.get(blockReferences[1]),
      ]);
      if (callerBlock.exists || targetBlock.exists) {
        throw new HttpsError("permission-denied", "A direct room is unavailable.");
      }
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
        avatarObjectPath: null,
        createdAt,
        deletedAt: null,
        directKey: identity.directKey,
        kind: "DIRECT",
        latestMessage: null,
        memberIds: identity.memberIds,
        ownerUid: null,
        revision: 1,
        title: `${callerProfile.displayName}, ${targetProfile.displayName}`,
        updatedAt: createdAt,
      });
      for (const uid of identity.memberIds) {
        transaction.create(roomReference.collection("members").doc(uid), {
          active: true,
          archived: false,
          joinedAt: createdAt,
          lastReadAt: null,
          leftAt: null,
          muted: false,
          mutedUntil: null,
          pinned: false,
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
    const {uid: callerUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(callerUid, "conversationMutation");
    const roomId =
      typeof request.data === "object" && request.data !== null && "roomId" in request.data
        ? (request.data as {roomId?: unknown}).roomId
        : null;
    if (
      typeof roomId !== "string" ||
      !/^(direct_[a-f0-9]{64}|group_[a-f0-9]{32})$/.test(roomId)
    ) {
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
    requireActiveProfile(profileSnapshot.data() as ProfileDocument | undefined);
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
      message = parseRemoteNotificationMessagePayload(messageSnapshot.data());
    } catch {
      return;
    }

    const roomId = event.params.roomId;
    const messageId = event.params.messageId;
    const roomReference = firestore.doc(`rooms/${roomId}`);
    const [roomSnapshot, localAiParticipantSnapshot, cinderParticipantSnapshot] = await Promise.all([
      roomReference.get(),
      roomReference.collection("participants").doc("participant-synapse-local-ai").get(),
      roomReference.collection("participants").doc("participant-cinder-remote-ai").get(),
    ]);
    if (!roomSnapshot.exists) {
      return;
    }
    const memberIds = roomSnapshot.get("memberIds");
    const roomKind = roomSnapshot.get("kind");
    if (
      !Array.isArray(memberIds) ||
      (roomKind !== "DIRECT" && roomKind !== "GROUP")
    ) {
      return;
    }
    const humanAuthorIsActiveMember = message.authorKind === "HUMAN" && memberIds.includes(message.senderUid);
    const localAiAuthorIsActiveParticipant = message.authorKind === "SYNAPSE_AI" &&
      localAiParticipantSnapshot.get("active") === true &&
      localAiParticipantSnapshot.get("participantId") === message.aiParticipantId &&
      localAiParticipantSnapshot.get("provenance") === message.provenance;
    const cinderAuthorIsActiveParticipant = message.authorKind === "REMOTE_AI" &&
      cinderParticipantSnapshot.get("active") === true &&
      cinderParticipantSnapshot.get("participantId") === message.aiParticipantId &&
      cinderParticipantSnapshot.get("provenance") === message.provenance &&
      cinderParticipantSnapshot.get("provider") === message.aiProvider &&
      cinderParticipantSnapshot.get("assistantId") === message.assistantId;
    if (!humanAuthorIsActiveMember && !localAiAuthorIsActiveParticipant && !cinderAuthorIsActiveParticipant) return;
    const candidateRecipientUids = [
      ...new Set(
        memberIds.filter(
          (memberUid): memberUid is string =>
            typeof memberUid === "string" &&
            (message.authorKind !== "HUMAN" || memberUid !== message.senderUid),
        ),
      ),
    ];
    const authorizationSnapshots = candidateRecipientUids.length === 0 ? [] : await firestore.getAll(
      ...candidateRecipientUids.flatMap((recipientUid) => [
        firestore.doc(`profiles/${recipientUid}`),
        roomReference.collection("members").doc(recipientUid),
        firestore.doc(`notificationPreferences/${recipientUid}`),
      ]),
    );
    const notificationEvaluatedAt = Timestamp.now().toMillis();
    const authorizationStates = candidateRecipientUids.map((recipientUid, recipientIndex) => {
      const profileSnapshot = authorizationSnapshots[recipientIndex * 3];
      const membershipSnapshot = authorizationSnapshots[recipientIndex * 3 + 1];
      const preferenceSnapshot = authorizationSnapshots[recipientIndex * 3 + 2];
      const mutedUntil = membershipSnapshot?.get("mutedUntil");
      const muted = isRoomMuteActive(
        membershipSnapshot?.get("muted"),
        mutedUntil instanceof Timestamp ? mutedUntil.toMillis() : null,
        notificationEvaluatedAt,
      );
      const preferences = preferenceSnapshot?.exists ?
        readNotificationPreferences(preferenceSnapshot.data()) :
        readNotificationPreferences(undefined);
      const username = profileSnapshot?.get("usernameNormalized") ?? profileSnapshot?.get("username");
      return {
        membershipActive: membershipSnapshot?.get("active") === true,
        nextUnreadCount: nextRemoteNotificationUnreadCount(membershipSnapshot?.get("unreadCount")),
        notificationsEnabled: typeof username === "string" && shouldNotifyForRemoteMessage({
          body: message.body,
          muted,
          preferences,
          recipientUsername: username,
          roomKind,
        }),
        profileAllowed:
          profileSnapshot?.get("allowed") === true &&
          profileSnapshot?.get("accountState") === "ACTIVE",
        uid: recipientUid,
      };
    });
    const notificationRecipientUids = selectAuthorizedMessageRecipientUids(
      candidateRecipientUids,
      authorizationStates,
    );

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
      const deliveryResult = await sendMetadataOnlyMessageNotification({
        messageId,
        recipients: authorizationStates
          .filter((authorization) => notificationRecipientUids.includes(authorization.uid))
          .map((authorization) => ({
            uid: authorization.uid,
            unreadCount: authorization.nextUnreadCount,
          })),
        roomId,
        senderUid: message.senderUid,
      });

      const updatedAt = Timestamp.now();
      await firestore.runTransaction(async (transaction) => {
        const [currentRoomSnapshot, currentMessageSnapshot] = await Promise.all([
          transaction.get(roomReference),
          transaction.get(messageSnapshot.ref),
        ]);
        if (!currentRoomSnapshot.exists || !currentMessageSnapshot.exists) return;
        const createdAt = currentMessageSnapshot.get("createdAt");
        if (!(createdAt instanceof Timestamp)) return;
        const currentBody = currentMessageSnapshot.get("body");
        const summaryBody = currentMessageSnapshot.get("deletedAt") instanceof Timestamp ?
          "Message deleted" :
          typeof currentBody === "string" && currentBody.length > 0 ? currentBody : message.body;
        const latestMessage = currentRoomSnapshot.get("latestMessage");
        const latestMessageId = typeof latestMessage?.messageId === "string" ? latestMessage.messageId : null;
        const latestCreatedAt = latestMessage?.createdAt;
        if (
          latestMessageId !== messageId &&
          latestCreatedAt instanceof Timestamp &&
          (
            latestCreatedAt.toMillis() > createdAt.toMillis() ||
            (latestCreatedAt.isEqual(createdAt) && latestMessageId > messageId)
          )
        ) {
          return;
        }
        const latestMessageSummary = {
          body: summaryBody,
          createdAt,
          messageId,
          senderUid: message.senderUid,
        };
        transaction.update(
          roomReference,
          latestMessageId === messageId ?
            {latestMessage: latestMessageSummary} :
            {latestMessage: latestMessageSummary, updatedAt: createdAt},
        );
      });
      const summaryWrites = firestore.batch();
      for (const recipientUid of notificationRecipientUids) {
        summaryWrites.update(roomReference.collection("members").doc(recipientUid), {
          unreadCount: FieldValue.increment(1),
        });
      }
      summaryWrites.update(receiptReference, {
        completedAt: updatedAt,
        failureCount: deliveryResult.failureCount,
        state: "COMPLETE",
        successCount: deliveryResult.successCount,
      });
      await summaryWrites.commit();
    } catch (error) {
      await receiptReference.delete();
      throw error;
    }
  },
);
