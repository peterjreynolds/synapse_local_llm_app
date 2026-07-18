import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  requireActiveGroupMembership,
  requireActiveGroupRoom,
} from "./groupRoomAuthorization.js";
import {GroupMemberRole, parseGroupRoomCommand} from "./groupRoomDomain.js";
import {isRoomMuteActive} from "./roomPreferenceDomain.js";

interface GroupMemberSummary {
  joinedAtMillis: number;
  role: GroupMemberRole;
  uid: string;
}

export const getGroupRoomDetails = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{
    archived: boolean;
    avatarObjectPath: string | null;
    members: GroupMemberSummary[];
    muted: boolean;
    ownerUid: string;
    pinned: boolean;
    revision: number;
    roomId: string;
    title: string;
  }> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const {roomId} = parseGroupRoomCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const [roomSnapshot, actorMembershipSnapshot] = await Promise.all([
      roomReference.get(),
      roomReference.collection("members").doc(actorUid).get(),
    ]);
    const room = requireActiveGroupRoom(roomSnapshot);
    const actorMembership = requireActiveGroupMembership(actorMembershipSnapshot, actorUid);
    if (!room.activeMemberIds.includes(actorUid)) {
      throw new HttpsError("permission-denied", "Active group membership is required.");
    }
    const memberSnapshots = await roomReference.collection("members").where("active", "==", true).get();
    const members = memberSnapshots.docs.map((member) => {
      const membership = requireActiveGroupMembership(member, member.id);
      const joinedAt = member.get("joinedAt");
      if (!(joinedAt instanceof Timestamp)) {
        throw new HttpsError("data-loss", "Group membership state is malformed.");
      }
      return {
        joinedAtMillis: joinedAt.toMillis(),
        role: membership.role,
        uid: member.id,
      };
    });
    if (
      members.length !== room.activeMemberIds.length ||
      members.some((member) => !room.activeMemberIds.includes(member.uid)) ||
      members.filter((member) => member.role === "OWNER").length !== 1
    ) {
      throw new HttpsError("data-loss", "Group membership state is inconsistent.");
    }
    return {
      archived: actorMembership.archived,
      avatarObjectPath: room.avatarObjectPath,
      members,
      muted: isRoomMuteActive(
        actorMembership.muted,
        actorMembershipSnapshot.get("mutedUntil") instanceof Timestamp ?
          (actorMembershipSnapshot.get("mutedUntil") as Timestamp).toMillis() : null,
        Date.now(),
      ),
      ownerUid: room.ownerUid,
      pinned: actorMembership.pinned,
      revision: room.revision,
      roomId,
      title: room.title,
    };
  },
);
