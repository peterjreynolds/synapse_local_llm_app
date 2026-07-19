import {Timestamp} from "firebase-admin/firestore";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {
  buildCinderHumanRoomContentDigest,
  buildCinderJobId,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_PARTICIPANT_ID,
  CINDER_RESPONSE_POLICY,
  cinderTimestampSequence,
  isCinderHumanRoomQueueEligible,
  normalizeCinderMessageBody,
} from "./cinderDomain.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";

export const queueCinderHumanRoomResponse = onDocumentCreated(
  {
    document: "rooms/{roomId}/messages/{messageId}",
    region: FIREBASE_FUNCTIONS_REGION,
    retry: true,
  },
  async (event): Promise<void> => {
    const messageSnapshot = event.data;
    if (!messageSnapshot || messageSnapshot.get("deletedAt") !== null) return;
    const authorKind = messageSnapshot.get("authorKind");
    const body = messageSnapshot.get("body");
    const sourceSenderUid = messageSnapshot.get("senderUid");
    const sourceServerCreatedAt = messageSnapshot.get("createdAt");
    const sourceRevision = messageSnapshot.get("revision") ?? 1;
    if (
      authorKind !== "HUMAN" ||
      typeof body !== "string" ||
      body.length === 0 ||
      body.length > 4_000 ||
      typeof sourceSenderUid !== "string" ||
      !(sourceServerCreatedAt instanceof Timestamp) ||
      typeof sourceRevision !== "number" ||
      !Number.isSafeInteger(sourceRevision) ||
      sourceRevision < 1
    ) {
      return;
    }
    let normalizedBody: string;
    try {
      normalizedBody = normalizeCinderMessageBody(body);
    } catch {
      return;
    }
    const roomId = event.params.roomId;
    const sourceMessageId = event.params.messageId;
    if (
      !/^[A-Za-z0-9_-]{1,128}$/.test(sourceMessageId) ||
      !/^[A-Za-z0-9_-]{1,128}$/.test(sourceSenderUid)
    ) {
      return;
    }
    const directRoomId = /^direct_[a-f0-9]{64}$/.test(roomId);
    const groupRoomId = /^group_[a-f0-9]{32}$/.test(roomId);
    if (!directRoomId && !groupRoomId) return;
    const expectedRoomKind = directRoomId ? "DIRECT" : "GROUP";
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const participantReference = roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID);
    const membershipReference = roomReference.collection("members").doc(sourceSenderUid);
    const profileReference = firebaseAdminFirestore.doc(`profiles/${sourceSenderUid}`);
    const jobId = buildCinderJobId({
      accountUid: sourceSenderUid,
      roomId,
      roomKind: expectedRoomKind,
      sourceMessageId,
    });
    const jobReference = firebaseAdminFirestore.doc(`cinderResponseJobs/${jobId}`);
    const auditReference = firebaseAdminFirestore.doc(`cinderResponseAudits/${jobId}`);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [roomSnapshot, participantSnapshot, membershipSnapshot, profileSnapshot, jobSnapshot, auditSnapshot] =
        await Promise.all([
          transaction.get(roomReference),
          transaction.get(participantReference),
          transaction.get(membershipReference),
          transaction.get(profileReference),
          transaction.get(jobReference),
          transaction.get(auditReference),
        ]);
      const roomKind = roomSnapshot.get("kind");
      if (
        !roomSnapshot.exists ||
        roomSnapshot.get("deletedAt") !== null ||
        roomKind !== expectedRoomKind ||
        !Array.isArray(roomSnapshot.get("activeMemberIds")) ||
        !(roomSnapshot.get("activeMemberIds") as unknown[]).includes(sourceSenderUid)
      ) {
        return;
      }
      const senderActive =
        membershipSnapshot.exists &&
        membershipSnapshot.get("active") === true &&
        profileSnapshot.exists &&
        profileSnapshot.get("allowed") === true &&
        profileSnapshot.get("accountState") === "ACTIVE" &&
        profileSnapshot.get("mustChangePassword") === false;
      if (!isCinderHumanRoomQueueEligible({
        authorKind,
        body: normalizedBody,
        participantActive: participantSnapshot.get("active") === true,
        participantId: participantSnapshot.get("participantId"),
        participantKind: participantSnapshot.get("kind"),
        participantProvenance: participantSnapshot.get("provenance"),
        responsePolicy: participantSnapshot.get("responsePolicy"),
        senderActive,
      })) {
        return;
      }
      if (
        participantSnapshot.get("assistantId") !== CINDER_ASSISTANT_ID ||
        participantSnapshot.get("provider") !== CINDER_AI_PROVIDER
      ) {
        return;
      }
      const sourceSenderDisplayName = profileSnapshot.get("displayName");
      if (
        typeof sourceSenderDisplayName !== "string" ||
        sourceSenderDisplayName.length === 0 ||
        sourceSenderDisplayName.length > 64
      ) {
        return;
      }
      const contentDigest = buildCinderHumanRoomContentDigest({
        accountUid: sourceSenderUid,
        body: normalizedBody,
        roomId,
        sourceMessageId,
        sourceRevision,
      });
      if (jobSnapshot.exists || auditSnapshot.exists) {
        const existing = jobSnapshot.exists ? jobSnapshot : auditSnapshot;
        if (
          existing.get("contentDigest") !== contentDigest ||
          existing.get("sourceMessageId") !== sourceMessageId
        ) {
          throw new Error("A Cinder response job identifier collided with different content.");
        }
        return;
      }
      const sourceSequence = cinderTimestampSequence(
        sourceServerCreatedAt.seconds,
        sourceServerCreatedAt.nanoseconds,
      );
      const queuedAt = Timestamp.now();
      transaction.create(jobReference, {
        accountUid: sourceSenderUid,
        assistantId: CINDER_ASSISTANT_ID,
        attemptCount: 0,
        contentDigest,
        createdAt: queuedAt,
        explicitMention: true,
        idempotencyKey: jobId,
        leaseClaimedAt: null,
        leaseDigest: null,
        leaseExpiresAt: null,
        leaseId: null,
        participantActive: true,
        participantId: CINDER_PARTICIPANT_ID,
        responsePolicy: CINDER_RESPONSE_POLICY,
        roomId,
        roomKind,
        sourceBody: normalizedBody,
        sourceMessageId,
        sourceRevision,
        sourceSenderDisplayName,
        sourceSenderUid,
        sourceSequence,
        sourceServerCreatedAt,
        state: "PENDING",
        updatedAt: queuedAt,
      });
    });
  },
);
