import {Timestamp} from "firebase-admin/firestore";
import {onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  parseUpdateRoomPreferencesCommand,
  ResolvedRoomMuteState,
  UpdateRoomPreferencesCommand,
  resolveRoomMuteState,
} from "./roomPreferenceDomain.js";
import {requireActiveRoomActor} from "./roomAuthorization.js";

export const updateRoomPreferences = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{
    archived: boolean;
    muteDuration: string | null;
    muted: boolean | null;
    mutedUntilMillis: number | null;
    pinned: boolean;
    roomId: string;
  }> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseUpdateRoomPreferencesCommand(request.data);
    const muteState = await persistRoomPreferences(actorUid, command);
    return {
      ...command,
      muted: muteState?.muted ?? null,
      mutedUntilMillis: muteState?.mutedUntilMillis ?? null,
    };
  },
);

export async function persistRoomPreferences(
  actorUid: string,
  command: UpdateRoomPreferencesCommand,
): Promise<ResolvedRoomMuteState | null> {
  const changedAt = Timestamp.now();
  const muteState = command.muteDuration === null ?
    null : resolveRoomMuteState(command.muteDuration, changedAt.toMillis());
  const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    await requireActiveRoomActor(transaction, roomReference, actorUid);
    transaction.update(roomReference.collection("members").doc(actorUid), {
      archived: command.archived,
      pinned: command.pinned,
      ...(muteState === null ? {} : {
        muted: muteState.muted,
        mutedUntil: muteState.mutedUntilMillis === null ? null : Timestamp.fromMillis(muteState.mutedUntilMillis),
      }),
    });
  });
  return muteState;
}
