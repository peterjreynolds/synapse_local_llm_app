import {DocumentReference, Transaction} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";

export type RemoteRoomKind = "DIRECT" | "GROUP";
export type RemoteRoomMemberRole = "OWNER" | "ADMIN" | "MEMBER";

export interface ActiveRoomAuthorization {
  activeMemberIds: string[];
  kind: RemoteRoomKind;
  latestMessageId: string | null;
  memberIds: string[];
  role: RemoteRoomMemberRole;
}

export async function requireActiveRoomActor(
  transaction: Transaction,
  roomReference: DocumentReference,
  actorUid: string,
): Promise<ActiveRoomAuthorization> {
  const membershipReference = roomReference.collection("members").doc(actorUid);
  const [roomSnapshot, membershipSnapshot] = await Promise.all([
    transaction.get(roomReference),
    transaction.get(membershipReference),
  ]);
  if (!roomSnapshot.exists || roomSnapshot.get("deletedAt") !== null) {
    throw new HttpsError("failed-precondition", "The room is unavailable.");
  }
  const kind = roomSnapshot.get("kind");
  const latestMessage = roomSnapshot.get("latestMessage");
  const latestMessageId = typeof latestMessage === "object" && latestMessage !== null &&
    typeof (latestMessage as {messageId?: unknown}).messageId === "string" ?
    (latestMessage as {messageId: string}).messageId : null;
  const memberIds = readDistinctUidList(roomSnapshot.get("memberIds"));
  const activeMemberIds = readDistinctUidList(roomSnapshot.get("activeMemberIds"));
  const role = membershipSnapshot.get("role");
  if (
    (kind !== "DIRECT" && kind !== "GROUP") ||
    memberIds.length === 0 ||
    activeMemberIds.length === 0 ||
    activeMemberIds.some((uid) => !memberIds.includes(uid)) ||
    !activeMemberIds.includes(actorUid) ||
    !membershipSnapshot.exists ||
    membershipSnapshot.get("active") !== true ||
    (role !== "OWNER" && role !== "ADMIN" && role !== "MEMBER") ||
    (kind === "DIRECT" && (memberIds.length !== 2 || activeMemberIds.length !== 2 || role !== "MEMBER"))
  ) {
    throw new HttpsError("permission-denied", "Active room membership is required.");
  }
  return {activeMemberIds, kind, latestMessageId, memberIds, role};
}

function readDistinctUidList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const uids = value.filter(
    (uid): uid is string => typeof uid === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(uid),
  );
  return uids.length === value.length && new Set(uids).size === uids.length ? uids : [];
}
