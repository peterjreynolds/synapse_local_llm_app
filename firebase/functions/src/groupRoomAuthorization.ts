import {DocumentReference, DocumentSnapshot, Transaction} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {assertActiveAccountProfile} from "./accountAuthorization.js";
import {GroupMemberRole, MAXIMUM_GROUP_MEMBERS} from "./groupRoomDomain.js";

export interface ActiveGroupRoom {
  activeMemberIds: string[];
  avatarObjectPath: string | null;
  memberIds: string[];
  ownerUid: string;
  revision: number;
  title: string;
}

export interface ActiveGroupMembership {
  archived: boolean;
  muted: boolean;
  pinned: boolean;
  role: GroupMemberRole;
}

export function requireActiveGroupRoom(snapshot: DocumentSnapshot): ActiveGroupRoom {
  const activeMemberIds = readDistinctUids(snapshot.get("activeMemberIds"));
  const memberIds = readDistinctUids(snapshot.get("memberIds"));
  const avatarObjectPath = snapshot.get("avatarObjectPath");
  const ownerUid = snapshot.get("ownerUid");
  const revision = snapshot.get("revision");
  const title = snapshot.get("title");
  if (
    !snapshot.exists ||
    snapshot.get("kind") !== "GROUP" ||
    snapshot.get("deletedAt") != null ||
    activeMemberIds.length === 0 ||
    activeMemberIds.length > MAXIMUM_GROUP_MEMBERS ||
    memberIds.length < activeMemberIds.length ||
    (avatarObjectPath !== null && typeof avatarObjectPath !== "string") ||
    typeof ownerUid !== "string" ||
    !activeMemberIds.includes(ownerUid) ||
    typeof revision !== "number" ||
    !Number.isSafeInteger(revision) ||
    revision < 1 ||
    typeof title !== "string"
  ) {
    throw new HttpsError("failed-precondition", "Group state is unavailable.");
  }
  return {activeMemberIds, avatarObjectPath, memberIds, ownerUid, revision, title};
}

export function requireActiveGroupMembership(
  snapshot: DocumentSnapshot,
  expectedUid: string,
): ActiveGroupMembership {
  const role = snapshot.get("role");
  const archived = snapshot.get("archived");
  const muted = snapshot.get("muted");
  const pinned = snapshot.get("pinned");
  if (
    !snapshot.exists ||
    snapshot.id !== expectedUid ||
    snapshot.get("uid") !== expectedUid ||
    snapshot.get("active") !== true ||
    (role !== "OWNER" && role !== "ADMIN" && role !== "MEMBER") ||
    typeof archived !== "boolean" ||
    typeof muted !== "boolean" ||
    typeof pinned !== "boolean"
  ) {
    throw new HttpsError("permission-denied", "Active group membership is required.");
  }
  return {archived, muted, pinned, role};
}

export async function requireActiveGroupActor(
  transaction: Transaction,
  roomReference: DocumentReference,
  actorUid: string,
): Promise<{membership: ActiveGroupMembership; room: ActiveGroupRoom}> {
  const [roomSnapshot, membershipSnapshot, profileSnapshot] = await Promise.all([
    transaction.get(roomReference),
    transaction.get(roomReference.collection("members").doc(actorUid)),
    transaction.get(roomReference.firestore.doc(`profiles/${actorUid}`)),
  ]);
  assertActiveAccountProfile(profileSnapshot.data());
  const room = requireActiveGroupRoom(roomSnapshot);
  const membership = requireActiveGroupMembership(membershipSnapshot, actorUid);
  if (!room.activeMemberIds.includes(actorUid)) {
    throw new HttpsError("permission-denied", "Active group membership is required.");
  }
  if ((membership.role === "OWNER") !== (room.ownerUid === actorUid)) {
    throw new HttpsError("failed-precondition", "Group ownership state is inconsistent.");
  }
  return {membership, room};
}

export function requireGroupAdministrator(role: GroupMemberRole): void {
  if (role !== "OWNER" && role !== "ADMIN") {
    throw new HttpsError("permission-denied", "Group administrator access is required.");
  }
}

function readDistinctUids(value: unknown): string[] {
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== "string")) {
    throw new HttpsError("failed-precondition", "Group membership state is malformed.");
  }
  const uids = value as string[];
  if (new Set(uids).size !== uids.length) {
    throw new HttpsError("failed-precondition", "Group membership state is malformed.");
  }
  return uids;
}
