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
  CINDER_LEGACY_RESPONSE_POLICY,
  CINDER_PARTICIPANT_ID,
  CinderParticipationMode,
  CinderWorkState,
  DEFAULT_CINDER_PARTICIPATION_MODE,
  isLegacyCinderParticipantResponsePolicy,
  parseCinderParticipantQuery,
  parseSetCinderParticipantCommand,
  resolveStoredCinderParticipationMode,
  resolveCinderWorkState,
} from "./cinderDomain.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

export interface CinderParticipantState {
  active: boolean;
  canManage: boolean;
  displayName: "Cinder";
  mode: CinderParticipationMode;
  participantId: typeof CINDER_PARTICIPANT_ID;
  provenance: typeof CINDER_AI_PROVENANCE;
  provider: typeof CINDER_AI_PROVIDER;
  responsePolicy: typeof CINDER_LEGACY_RESPONSE_POLICY;
  revision: number;
  roomId: string;
  workState: CinderWorkState;
}

export const getCinderParticipant = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<CinderParticipantState> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    await enforceCallableRateLimit(actorUid, "cinderPresencePolling");
    const {roomId} = parseCinderParticipantQuery(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const participantReference = roomReference.collection("participants").doc(CINDER_PARTICIPANT_ID);
    const participantState = await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const authorization = await requireActiveRoomActor(transaction, roomReference, actorUid);
      const participantSnapshot = await transaction.get(participantReference);
      return buildCinderParticipantState(
        roomId,
        canManageCinderParticipant(authorization.kind, authorization.role),
        participantSnapshot.exists ? participantSnapshot.data() : undefined,
      );
    });
    if (!participantState.active || participantState.mode === "SILENT") return participantState;
    const jobs = await firebaseAdminFirestore.collection("cinderResponseJobs")
      .where("roomId", "==", roomId)
      .limit(10)
      .get();
    return {
      ...participantState,
      workState: resolveCinderWorkState(jobs.docs.map((job) => job.get("state"))),
    };
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
      const requestedActive = command.active ?? existingState.active;
      const requestedMode = command.mode ?? existingState.mode;
      if (existingState.active === requestedActive && existingState.mode === requestedMode) {
        return existingState;
      }
      if (
        command.expectedRevision !== null &&
        command.expectedRevision !== existingState.revision
      ) {
        throw new HttpsError(
          "aborted",
          "Cinder participant state changed. Refresh the room and try again.",
        );
      }
      const revision = existingState.revision + 1;
      transaction.set(participantReference, {
        active: requestedActive,
        assistantId: CINDER_ASSISTANT_ID,
        createdAt: existingParticipant.exists ? existingParticipant.get("createdAt") : changedAt,
        displayName: "Cinder",
        kind: "REMOTE_AI",
        mode: requestedMode,
        participantId: CINDER_PARTICIPANT_ID,
        provenance: CINDER_AI_PROVENANCE,
        provider: CINDER_AI_PROVIDER,
        removedAt: requestedActive ? null : changedAt,
        responsePolicy: CINDER_LEGACY_RESPONSE_POLICY,
        revision,
        updatedAt: changedAt,
      });
      transaction.update(roomReference, {
        aiParticipantIds: requestedActive ?
          FieldValue.arrayUnion(CINDER_PARTICIPANT_ID) :
          FieldValue.arrayRemove(CINDER_PARTICIPANT_ID),
        updatedAt: changedAt,
      });
      transaction.create(firebaseAdminFirestore.doc(`cinderAuditEvents/${randomUUID()}`), {
        actorUid,
        createdAt: changedAt,
        eventType: existingState.active !== requestedActive ?
          requestedActive ? "CINDER_PARTICIPANT_ADDED" : "CINDER_PARTICIPANT_REMOVED" :
          "CINDER_MODE_CHANGED",
        mode: requestedMode,
        participantId: CINDER_PARTICIPANT_ID,
        previousMode: existingState.mode,
        responsePolicy: CINDER_LEGACY_RESPONSE_POLICY,
        revision,
        roomId: command.roomId,
      });
      return {
        ...existingState,
        active: requestedActive,
        mode: requestedMode,
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
      mode: DEFAULT_CINDER_PARTICIPATION_MODE,
      participantId: CINDER_PARTICIPANT_ID,
      provenance: CINDER_AI_PROVENANCE,
      provider: CINDER_AI_PROVIDER,
      responsePolicy: CINDER_LEGACY_RESPONSE_POLICY,
      revision: 0,
      roomId,
      workState: "IDLE",
    };
  }
  if (typeof input !== "object" || input === null || Array.isArray(input)) malformedParticipant();
  const participant = input as Record<string, unknown>;
  const active = participant.active;
  const removedAt = participant.removedAt;
  const revision = participant.revision ?? 1;
  const mode = resolveStoredCinderParticipationMode({
    mode: participant.mode,
    responsePolicy: participant.responsePolicy,
  });
  if (
    typeof active !== "boolean" ||
    participant.assistantId !== CINDER_ASSISTANT_ID ||
    participant.displayName !== "Cinder" ||
    participant.kind !== "REMOTE_AI" ||
    participant.participantId !== CINDER_PARTICIPANT_ID ||
    participant.provenance !== CINDER_AI_PROVENANCE ||
    participant.provider !== CINDER_AI_PROVIDER ||
    !isLegacyCinderParticipantResponsePolicy(participant.responsePolicy) ||
    mode === null ||
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
    mode,
    participantId: CINDER_PARTICIPANT_ID,
    provenance: CINDER_AI_PROVENANCE,
    provider: CINDER_AI_PROVIDER,
    responsePolicy: CINDER_LEGACY_RESPONSE_POLICY,
    revision,
    roomId,
    workState: "IDLE",
  };
}

function malformedParticipant(): never {
  throw new HttpsError("data-loss", "Cinder participant state is malformed.");
}
