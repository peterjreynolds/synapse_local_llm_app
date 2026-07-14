import {Timestamp} from "firebase-admin/firestore";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {parseHumanMessagePayload} from "./domain.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {readRoomAiConfigurationSnapshot} from "./remoteAiConfiguration.js";
import {
  buildRemoteAiJobId,
  LOCAL_AI_PARTICIPANT_ID,
  responsePolicyForConfiguration,
} from "./remoteAiDomain.js";

export const queueRemoteLocalAiResponse = onDocumentCreated(
  {
    document: "rooms/{roomId}/messages/{messageId}",
    region: FIREBASE_FUNCTIONS_REGION,
    retry: true,
  },
  async (event): Promise<void> => {
    const messageSnapshot = event.data;
    if (!messageSnapshot || messageSnapshot.get("deletedAt") !== null) return;
    let message: ReturnType<typeof parseHumanMessagePayload>;
    try {
      message = parseHumanMessagePayload(messageSnapshot.data());
    } catch {
      return;
    }
    const roomId = event.params.roomId;
    const sourceMessageId = event.params.messageId;
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const configurationReference = firebaseAdminFirestore.doc(`roomAiConfigurations/${roomId}`);
    const participantReference = roomReference.collection("participants").doc(LOCAL_AI_PARTICIPANT_ID);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const [roomSnapshot, configurationSnapshot, participantSnapshot, senderMembershipSnapshot] =
        await Promise.all([
          transaction.get(roomReference),
          transaction.get(configurationReference),
          transaction.get(participantReference),
          transaction.get(roomReference.collection("members").doc(message.senderUid)),
        ]);
      if (
        !roomSnapshot.exists ||
        roomSnapshot.get("deletedAt") !== null ||
        senderMembershipSnapshot.get("active") !== true ||
        participantSnapshot.get("active") !== true ||
        participantSnapshot.get("kind") !== "LOCAL_AI" ||
        participantSnapshot.get("participantId") !== LOCAL_AI_PARTICIPANT_ID ||
        participantSnapshot.get("provenance") !== "PHONE_LOCAL"
      ) {
        return;
      }
      const configuration = readRoomAiConfigurationSnapshot(roomId, configurationSnapshot, Date.now());
      if (
        !configuration.localAiEnabled ||
        configuration.localAiHostDeviceId === null ||
        configuration.localAiHostUid === null
      ) {
        return;
      }
      const jobId = buildRemoteAiJobId(roomId, sourceMessageId);
      const jobReference = firebaseAdminFirestore.doc(
        `localAiHostQueues/${configuration.localAiHostDeviceId}/jobs/${jobId}`,
      );
      const auditReference = firebaseAdminFirestore.doc(`remoteAiResponseAudits/${jobId}`);
      const [existingJob, existingAudit] = await Promise.all([
        transaction.get(jobReference),
        transaction.get(auditReference),
      ]);
      if (existingJob.exists || existingAudit.exists) return;
      const createdAt = Timestamp.now();
      transaction.create(jobReference, {
        attemptCount: 0,
        createdAt,
        hostDeviceId: configuration.localAiHostDeviceId,
        hostUid: configuration.localAiHostUid,
        jobId,
        responsePolicy: responsePolicyForConfiguration(configuration.localAiAutoResponse),
        roomId,
        sourceAuthorUid: message.senderUid,
        sourceMessageId,
        state: "PENDING",
      });
    });
  },
);
