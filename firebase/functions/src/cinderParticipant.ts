import {randomUUID} from "node:crypto";
import {FieldValue, Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {enforceCallableRateLimit} from "./callableRateLimit.js";
import {
  canManageCinderParticipant,
  CINDER_AI_PROVENANCE,
  CINDER_AI_PROVIDER,
  CINDER_ASSISTANT_ID,
  CINDER_PARTICIPANT_ID,
  CINDER_RESPONSE_POLICY,
  parseCinderParticipantQuery,
  parseSetCinderParticipantCommand,
} from "./cinderDomain.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

export interface CinderParticipantState {
  active: boolean;
  canManage: boolean;
  displayName: "Cinder";
  participantId: typeof CINDER_PARTICIPANT_ID;
  provenance: typeof CINDER_AI_PROVENANCE;
  provider: typeof CINDER_AI_PROVIDER;
  responsePolicy: typeof CINDER_RESPONSE_POLICY;
  revision: number;
  roomId: string;
}

export const getCinderParticipant = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<CinderParticipantState> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(actorUid, "aiHostPolling");
    const {roomId} = parseCinderParticipantQuery(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const participantReference = roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID);
    return firebaseAdminFirestore.runTransaction(async (transaction) => {
      const authorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
      const participantSnapshot = await transaction.get(participantReference);
      return buildCinderParticipantState(
        roomId,
        canManageCinderParticipant(authorization.kind, authorization.role),
        participantSnapshot.exists ? participantSnapshot.data() : undefined,
      );
    });
  },
);

export const setCinderParticipant = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<CinderParticipantState> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(actorUid, "aiMutation");
    const command = parseSetCinderParticipantCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    const participantReference = roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID);
    const changedAt = Timestamp.now();
    return firebaseAdminFirestore.runTransaction(async (transaction) => {
      const authorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
      if (!canManageCinderParticipant(authorization.kind, authorization.role)) {
        throw new HttpsError(
          "permission-denied",
          "Group administrator access is required to change Cinder participation.",
        );
      }
      const existingParticipant = await transaction.get(participantReference);
      const existingState = buildCinderParticipantState(
        command.roomId,
        true,
        existingParticipant.exists ? existingParticipant.data() : undefined,
      );
      if (existingState.active === command.active) return existingState;
      const revision = existingState.revision + 1;
      transaction.set(participantReference, {
        active: command.active,
        assistantId: CINDER_ASSISTANT_ID,
        createdAt: existingParticipant.exists ? existingParticipant.get("createdAt") : changedAt,
        displayName: "Cinder",
        kind: "REMOTE_AI",
        participantId: CINDER_PARTICIPANT_ID,
        provenance: CINDER_AI_PROVENANCE,
        provider: CINDER_AI_PROVIDER,
        removedAt: command.active ? null : changedAt,
        responsePolicy: CINDER_RESPONSE_POLICY,
        revision,
        updatedAt: changedAt,
      });
      transaction.update(roomReference, {
        aiParticipantIds: command.active ?
          FieldValue.arrayUnion(CINDER_PARTICIPANT_ID) :
          FieldValue.arrayRemove(CINDER_PARTICIPANT_ID),
        updatedAt: changedAt,
      });
      transaction.create(firebaseAdminFirestore.doc(`cinderAuditEvents/${randomUUID()}`), {
        actorUid,
        createdAt: changedAt,
        eventType: command.active ? "CINDER_PARTICIPANT_ADDED" : "CINDER_PARTICIPANT_REMOVED",
        participantId: CINDER_PARTICIPANT_ID,
        responsePolicy: CINDER_RESPONSE_POLICY,
        revision,
        roomId: command.roomId,
      });
      return {
        ...existingState,
        active: command.active,
        revision,
      };
    });
  },
);

export function buildCinderParticipantState(
  roomId: string,
  canManage: boolean,
  input: unknown,
): CinderParticipantState {
  if (input === undefined) {
    return {
      active: false,
      canManage,
      displayName: "Cinder",
      participantId: CINDER_PARTICIPANT_ID,
      provenance: CINDER_AI_PROVENANCE,
      provider: CINDER_AI_PROVIDER,
      responsePolicy: CINDER_RESPONSE_POLICY,
      revision: 0,
      roomId,
    };
  }
  if (typeof input !== "object" || input === null || Array.isArray(input)) malformedParticipant();
  const participant = input as Record<string, unknown>;
  const active = participant.active;
  const removedAt = participant.removedAt;
  const revision = participant.revision ?? 1;
  if (
    typeof active !== "boolean" ||
    participant.assistantId !== CINDER_ASSISTANT_ID ||
    participant.displayName !== "Cinder" ||
    participant.kind !== "REMOTE_AI" ||
    participant.participantId !== CINDER_PARTICIPANT_ID ||
    participant.provenance !== CINDER_AI_PROVENANCE ||
    participant.provider !== CINDER_AI_PROVIDER ||
    participant.responsePolicy !== CINDER_RESPONSE_POLICY ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 1 ||
    !(participant.createdAt instanceof Timestamp) ||
    !(participant.updatedAt instanceof Timestamp) ||
    (active && removedAt !== null) ||
    (!active && !(removedAt instanceof Timestamp))
  ) {
    malformedParticipant();
  }
  return {
    active,
    canManage,
    displayName: "Cinder",
    participantId: CINDER_PARTICIPANT_ID,
    provenance: CINDER_AI_PROVENANCE,
    provider: CINDER_AI_PROVIDER,
    responsePolicy: CINDER_RESPONSE_POLICY,
    revision,
    roomId,
  };
}

function malformedParticipant(): never {
  throw new HttpsError("data-loss", "Cinder participant state is malformed.");
}
