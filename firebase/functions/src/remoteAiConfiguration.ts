import {randomUUID} from "node:crypto";
import {DocumentReference, DocumentSnapshot, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  LOCAL_AI_PARTICIPANT_ID,
  parseLocalAiHostCommand,
  parseRoomAiQuery,
  parseUpdateRemoteAiConfigurationCommand,
  readRemoteRoomAiConfiguration,
  RemoteRoomAiConfiguration,
  responsePolicyForConfiguration,
} from "./remoteAiDomain.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

export const getRoomAiConfiguration = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<RemoteRoomAiConfiguration> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const {roomId} = parseRoomAiQuery(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const configurationReference = firebaseAdminFirestore.doc(`roomAiConfigurations/${roomId}`);
    return firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveRoomActor(transaction, roomReference, actorUid);
      const configurationSnapshot = await transaction.get(configurationReference);
      return readRoomAiConfigurationSnapshot(roomId, configurationSnapshot, Date.now());
    });
  },
);

export const updateRoomAiConfiguration = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<RemoteRoomAiConfiguration> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseUpdateRemoteAiConfigurationCommand(request.data);
    if (command.hostedAiEnabled) {
      throw new HttpsError(
        "failed-precondition",
        "Hosted AI is unavailable until an approved provider and server secret are configured.",
      );
    }
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const configurationReference = firebaseAdminFirestore.doc(`roomAiConfigurations/${command.roomId}`);
    const participantReference = roomReference.collection("participants").doc(LOCAL_AI_PARTICIPANT_ID);
    const changedAt = Timestamp.now();
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const authorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
      if (
        authorization.kind === "GROUP" &&
        authorization.role !== "OWNER" &&
        authorization.role !== "ADMIN"
      ) {
        throw new HttpsError("permission-denied", "Group administrator access is required.");
      }
      if (command.localAiEnabled) {
        await requireRegisteredAiHostDevice(
          transaction,
          command.localAiHostDeviceId as string,
          actorUid,
        );
      }
      const existingConfiguration = await transaction.get(configurationReference);
      transaction.set(configurationReference, {
        createdAt: existingConfiguration.exists ? existingConfiguration.get("createdAt") : changedAt,
        hostedAiEnabled: false,
        hostedAiProviderConfigured: false,
        hostedAiStatus: "DISABLED_NO_PROVIDER",
        localAiAutoResponse: command.localAiAutoResponse,
        localAiEnabled: command.localAiEnabled,
        localAiHostDeviceId: command.localAiHostDeviceId,
        localAiHostLastSeenAt: command.localAiEnabled ? changedAt : null,
        localAiHostUid: command.localAiEnabled ? actorUid : null,
        roomId: command.roomId,
        updatedAt: changedAt,
      });
      transaction.set(participantReference, {
        active: command.localAiEnabled,
        displayName: "Synapse",
        hostDeviceId: command.localAiHostDeviceId,
        hostUid: command.localAiEnabled ? actorUid : null,
        kind: "LOCAL_AI",
        participantId: LOCAL_AI_PARTICIPANT_ID,
        provenance: "PHONE_LOCAL",
        responsePolicy: responsePolicyForConfiguration(command.localAiAutoResponse),
        updatedAt: changedAt,
      }, {merge: true});
      transaction.update(roomReference, {
        aiParticipantIds: command.localAiEnabled ? [LOCAL_AI_PARTICIPANT_ID] : [],
        updatedAt: changedAt,
      });
      transaction.create(firebaseAdminFirestore.doc(`remoteAiAuditEvents/${randomUUID()}`), {
        actorUid,
        createdAt: changedAt,
        eventType: "ROOM_AI_CONFIGURATION_UPDATED",
        hostedAiEnabled: false,
        localAiEnabled: command.localAiEnabled,
        responsePolicy: responsePolicyForConfiguration(command.localAiAutoResponse),
        roomId: command.roomId,
      });
    });
    const savedConfiguration = await configurationReference.get();
    return readRoomAiConfigurationSnapshot(command.roomId, savedConfiguration, Date.now());
  },
);

export const heartbeatLocalAiHost = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{hostedRoomCount: number; updatedAtMillis: number}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const {deviceId} = parseLocalAiHostCommand(request.data);
    const deviceReference = firebaseAdminFirestore.doc(`devices/${deviceId}`);
    const deviceSnapshot = await deviceReference.get();
    requireRegisteredAiHostDeviceSnapshot(deviceSnapshot, deviceId, actorUid);
    const configurationSnapshots = await firebaseAdminFirestore.collection("roomAiConfigurations")
      .where("localAiHostDeviceId", "==", deviceId)
      .limit(MAXIMUM_HOSTED_ROOMS_PER_DEVICE)
      .get();
    const heartbeatAt = Timestamp.now();
    const batch = firebaseAdminFirestore.batch();
    let hostedRoomCount = 0;
    configurationSnapshots.docs.forEach((configurationSnapshot) => {
      if (
        configurationSnapshot.get("localAiEnabled") === true &&
        configurationSnapshot.get("localAiHostUid") === actorUid
      ) {
        batch.update(configurationSnapshot.ref, {
          localAiHostLastSeenAt: heartbeatAt,
          updatedAt: heartbeatAt,
        });
        hostedRoomCount += 1;
      }
    });
    if (hostedRoomCount > 0) await batch.commit();
    return {hostedRoomCount, updatedAtMillis: heartbeatAt.toMillis()};
  },
);

export function readRoomAiConfigurationSnapshot(
  roomId: string,
  snapshot: DocumentSnapshot,
  nowMillis: number,
): RemoteRoomAiConfiguration {
  if (!snapshot.exists) return readRemoteRoomAiConfiguration(roomId, undefined, nowMillis);
  const data = snapshot.data();
  const lastSeenAt = snapshot.get("localAiHostLastSeenAt");
  return readRemoteRoomAiConfiguration(roomId, {
    ...data,
    localAiHostLastSeenAtMillis: lastSeenAt instanceof Timestamp ? lastSeenAt.toMillis() : lastSeenAt,
  }, nowMillis);
}

export async function requireRegisteredAiHostDevice(
  transaction: Transaction,
  deviceId: string,
  actorUid: string,
): Promise<DocumentReference> {
  const deviceReference = firebaseAdminFirestore.doc(`devices/${deviceId}`);
  const snapshot = await transaction.get(deviceReference);
  requireRegisteredAiHostDeviceSnapshot(snapshot, deviceId, actorUid);
  return deviceReference;
}

function requireRegisteredAiHostDeviceSnapshot(
  snapshot: DocumentSnapshot,
  deviceId: string,
  actorUid: string,
): void {
  if (
    !snapshot.exists ||
    snapshot.id !== deviceId ||
    snapshot.get("active") !== true ||
    snapshot.get("ownerUid") !== actorUid ||
    snapshot.get("platform") !== "ANDROID"
  ) {
    throw new HttpsError("permission-denied", "An active registered host device is required.");
  }
}

const MAXIMUM_HOSTED_ROOMS_PER_DEVICE = 50;
