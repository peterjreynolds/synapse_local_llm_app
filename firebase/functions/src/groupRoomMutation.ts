import {randomUUID} from "node:crypto";
import {DocumentReference, Timestamp, Transaction} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  assertActiveAccountProfile,
  requireActiveAccount,
  requireRecentActiveAccount,
} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  requireActiveGroupActor,
  requireActiveGroupMembership,
  requireGroupAdministrator,
} from "./groupRoomAuthorization.js";
import {
  MAXIMUM_GROUP_MEMBERS,
  parseCreateGroupRoomCommand,
  parseDeleteGroupRoomCommand,
  parseGroupMemberCommand,
  parseGroupMemberListCommand,
  parseGroupRoomCommand,
  parseRenameGroupRoomCommand,
  parseSetGroupAvatarCommand,
  parseSetGroupMemberRoleCommand,
  parseUpdateGroupPreferencesCommand,
} from "./groupRoomDomain.js";
import {buildReciprocalBlockReferences} from "./privacyAdmin.js";

const RECENT_GROUP_DELETION_AUTHENTICATION_SECONDS = 5 * 60;

export const createGroupRoom = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{memberCount: number; revision: 1; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseCreateGroupRoomCommand(request.data);
    if (command.memberUids.includes(actorUid)) {
      throw new HttpsError("invalid-argument", "Do not include the creator in the member list.");
    }
    const memberUids = [actorUid, ...command.memberUids];
    const roomId = `group_${randomUUID().replace(/-/g, "")}`;
    const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
    const createdAt = Timestamp.now();

    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const profileReferences = memberUids.map((uid) => firebaseAdminFirestore.doc(`profiles/${uid}`));
      const blockReferences = buildBlockReferencesForMemberSet(memberUids);
      const [roomSnapshot, profileSnapshots, blockSnapshots] = await Promise.all([
        transaction.get(roomReference),
        Promise.all(profileReferences.map((reference) => transaction.get(reference))),
        Promise.all(blockReferences.map((reference) => transaction.get(reference))),
      ]);
      if (roomSnapshot.exists) throw new HttpsError("already-exists", "Group identifier collision.");
      profileSnapshots.forEach((profile) => assertActiveAccountProfile(profile.data()));
      if (blockSnapshots.some((block) => block.exists)) {
        throw new HttpsError("permission-denied", "The selected group members cannot be combined.");
      }

      transaction.create(roomReference, {
        activeMemberIds: memberUids,
        avatarObjectPath: null,
        createdAt,
        deletedAt: null,
        directKey: null,
        kind: "GROUP",
        latestMessage: null,
        memberIds: memberUids,
        ownerUid: actorUid,
        revision: 1,
        title: command.title,
        updatedAt: createdAt,
      });
      memberUids.forEach((memberUid) => {
        transaction.create(roomReference.collection("members").doc(memberUid), {
          active: true,
          archived: false,
          joinedAt: createdAt,
          lastReadAt: null,
          leftAt: null,
          muted: false,
          pinned: false,
          role: memberUid === actorUid ? "OWNER" : "MEMBER",
          uid: memberUid,
          unreadCount: 0,
        });
      });
      createGroupAuditEvent(transaction, actorUid, roomId, "GROUP_CREATED", createdAt);
    });
    return {memberCount: memberUids.length, revision: 1, roomId};
  },
);

export const addGroupMembers = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{activeMemberCount: number; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseGroupMemberListCommand(request.data);
    if (command.memberUids.includes(actorUid)) {
      throw new HttpsError("invalid-argument", "The actor is already a group member.");
    }
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    let receipt = {activeMemberCount: 0, revision: 0, roomId: command.roomId};
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const {membership: actorMembership, room} = await requireActiveGroupActor(
        transaction,
        roomReference,
        actorUid,
      );
      requireGroupAdministrator(actorMembership.role);
      const newMemberUids = command.memberUids.filter((uid) => !room.activeMemberIds.includes(uid));
      if (newMemberUids.length === 0) {
        receipt = {
          activeMemberCount: room.activeMemberIds.length,
          revision: room.revision,
          roomId: command.roomId,
        };
        return;
      }
      const activeMemberIds = [...room.activeMemberIds, ...newMemberUids];
      if (activeMemberIds.length > MAXIMUM_GROUP_MEMBERS) {
        throw new HttpsError("resource-exhausted", "The group member limit was reached.");
      }
      const profileSnapshots = await Promise.all(
        newMemberUids.map((uid) => transaction.get(firebaseAdminFirestore.doc(`profiles/${uid}`))),
      );
      profileSnapshots.forEach((profile) => assertActiveAccountProfile(profile.data()));
      const blockReferences = buildBlockReferencesBetween(newMemberUids, activeMemberIds);
      const blockSnapshots = await Promise.all(
        blockReferences.map((reference) => transaction.get(reference)),
      );
      if (blockSnapshots.some((block) => block.exists)) {
        throw new HttpsError("permission-denied", "The selected group members cannot be combined.");
      }
      const membershipSnapshots = await Promise.all(
        newMemberUids.map((uid) => transaction.get(roomReference.collection("members").doc(uid))),
      );
      const changedAt = Timestamp.now();
      newMemberUids.forEach((memberUid, index) => {
        transaction.set(roomReference.collection("members").doc(memberUid), {
          active: true,
          archived: false,
          joinedAt: changedAt,
          lastReadAt: null,
          leftAt: null,
          muted: false,
          pinned: false,
          role: "MEMBER",
          uid: memberUid,
          unreadCount: 0,
        }, {merge: membershipSnapshots[index]?.exists === true});
      });
      const revision = room.revision + 1;
      transaction.update(roomReference, {
        activeMemberIds,
        memberIds: [...new Set([...room.memberIds, ...newMemberUids])],
        revision,
        updatedAt: changedAt,
      });
      createGroupAuditEvent(transaction, actorUid, command.roomId, "GROUP_MEMBERS_ADDED", changedAt);
      receipt = {activeMemberCount: activeMemberIds.length, revision, roomId: command.roomId};
    });
    return receipt;
  },
);

export const removeGroupMember = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{revision: number; roomId: string; targetUid: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseGroupMemberCommand(request.data);
    if (command.targetUid === actorUid) {
      throw new HttpsError("invalid-argument", "Use leave group for the current account.");
    }
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    let revision = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const {membership: actorMembership, room} = await requireActiveGroupActor(
        transaction,
        roomReference,
        actorUid,
      );
      requireGroupAdministrator(actorMembership.role);
      const targetReference = roomReference.collection("members").doc(command.targetUid);
      const targetSnapshot = await transaction.get(targetReference);
      const targetMembership = requireActiveGroupMembership(targetSnapshot, command.targetUid);
      if (
        targetMembership.role === "OWNER" ||
        (actorMembership.role === "ADMIN" && targetMembership.role !== "MEMBER")
      ) {
        throw new HttpsError("permission-denied", "This group member cannot be removed by the actor.");
      }
      const changedAt = Timestamp.now();
      revision = room.revision + 1;
      transaction.update(targetReference, {
        active: false,
        archived: false,
        leftAt: changedAt,
        muted: false,
        pinned: false,
        removedBy: actorUid,
      });
      transaction.update(roomReference, {
        activeMemberIds: room.activeMemberIds.filter((uid) => uid !== command.targetUid),
        revision,
        updatedAt: changedAt,
      });
      createGroupAuditEvent(transaction, actorUid, command.roomId, "GROUP_MEMBER_REMOVED", changedAt);
    });
    return {...command, revision};
  },
);

export const setGroupMemberRole = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{revision: number; role: "ADMIN" | "MEMBER"; roomId: string; targetUid: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseSetGroupMemberRoleCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    let revision = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const {membership: actorMembership, room} = await requireActiveGroupActor(
        transaction,
        roomReference,
        actorUid,
      );
      if (actorMembership.role !== "OWNER") {
        throw new HttpsError("permission-denied", "Only the group owner can change member roles.");
      }
      if (command.targetUid === actorUid) {
        throw new HttpsError("invalid-argument", "Transfer ownership before changing the owner role.");
      }
      const targetReference = roomReference.collection("members").doc(command.targetUid);
      const targetMembership = requireActiveGroupMembership(
        await transaction.get(targetReference),
        command.targetUid,
      );
      if (targetMembership.role === "OWNER") {
        throw new HttpsError("failed-precondition", "The group ownership state is inconsistent.");
      }
      if (targetMembership.role === command.role) {
        revision = room.revision;
        return;
      }
      const changedAt = Timestamp.now();
      revision = room.revision + 1;
      transaction.update(targetReference, {role: command.role});
      transaction.update(roomReference, {revision, updatedAt: changedAt});
      createGroupAuditEvent(transaction, actorUid, command.roomId, "GROUP_MEMBER_ROLE_CHANGED", changedAt);
    });
    return {...command, revision};
  },
);

export const transferGroupOwnership = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{ownerUid: string; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseGroupMemberCommand(request.data);
    if (command.targetUid === actorUid) {
      throw new HttpsError("invalid-argument", "Select another active group member.");
    }
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    let revision = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const {membership: actorMembership, room} = await requireActiveGroupActor(
        transaction,
        roomReference,
        actorUid,
      );
      if (actorMembership.role !== "OWNER") {
        throw new HttpsError("permission-denied", "Only the group owner can transfer ownership.");
      }
      const targetReference = roomReference.collection("members").doc(command.targetUid);
      requireActiveGroupMembership(await transaction.get(targetReference), command.targetUid);
      const changedAt = Timestamp.now();
      revision = room.revision + 1;
      transaction.update(roomReference.collection("members").doc(actorUid), {role: "ADMIN"});
      transaction.update(targetReference, {role: "OWNER"});
      transaction.update(roomReference, {
        ownerUid: command.targetUid,
        revision,
        updatedAt: changedAt,
      });
      createGroupAuditEvent(transaction, actorUid, command.roomId, "GROUP_OWNERSHIP_TRANSFERRED", changedAt);
    });
    return {ownerUid: command.targetUid, revision, roomId: command.roomId};
  },
);

export const leaveGroupRoom = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{left: true; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseGroupRoomCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    let revision = 0;
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const {membership, room} = await requireActiveGroupActor(transaction, roomReference, actorUid);
      if (membership.role === "OWNER") {
        throw new HttpsError("failed-precondition", "Transfer group ownership before leaving.");
      }
      const changedAt = Timestamp.now();
      revision = room.revision + 1;
      transaction.update(roomReference.collection("members").doc(actorUid), {
        active: false,
        archived: false,
        leftAt: changedAt,
        muted: false,
        pinned: false,
      });
      transaction.update(roomReference, {
        activeMemberIds: room.activeMemberIds.filter((uid) => uid !== actorUid),
        revision,
        updatedAt: changedAt,
      });
      createGroupAuditEvent(transaction, actorUid, command.roomId, "GROUP_LEFT", changedAt);
    });
    return {left: true, revision, roomId: command.roomId};
  },
);

export const renameGroupRoom = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{revision: number; roomId: string; title: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseRenameGroupRoomCommand(request.data);
    const revision = await updateGroupPresentation(
      actorUid,
      command.roomId,
      (transaction, roomReference, nextRevision, changedAt) => transaction.update(roomReference, {
        revision: nextRevision,
        title: command.title,
        updatedAt: changedAt,
      }),
      "GROUP_RENAMED",
    );
    return {...command, revision};
  },
);

export const setGroupAvatar = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{avatarObjectPath: string | null; revision: number; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseSetGroupAvatarCommand(request.data);
    const revision = await updateGroupPresentation(
      actorUid,
      command.roomId,
      (transaction, roomReference, nextRevision, changedAt) => transaction.update(roomReference, {
        avatarObjectPath: command.avatarObjectPath,
        revision: nextRevision,
        updatedAt: changedAt,
      }),
      "GROUP_AVATAR_CHANGED",
    );
    return {...command, revision};
  },
);

export const updateGroupPreferences = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{archived: boolean; muted: boolean; pinned: boolean; roomId: string}> => {
    const {uid: actorUid} = await requireActiveAccount(request.auth);
    const command = parseUpdateGroupPreferencesCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      await requireActiveGroupActor(transaction, roomReference, actorUid);
      transaction.update(roomReference.collection("members").doc(actorUid), {
        archived: command.archived,
        muted: command.muted,
        pinned: command.pinned,
      });
    });
    return command;
  },
);

export const deleteGroupRoom = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{deleted: true; roomId: string}> => {
    const {uid: actorUid} = await requireRecentActiveAccount(
      request.auth,
      RECENT_GROUP_DELETION_AUTHENTICATION_SECONDS,
    );
    const command = parseDeleteGroupRoomCommand(request.data);
    const roomReference = firebaseAdminFirestore.doc(`rooms/${command.roomId}`);
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const {membership, room} = await requireActiveGroupActor(transaction, roomReference, actorUid);
      if (membership.role !== "OWNER") {
        throw new HttpsError("permission-denied", "Only the group owner can delete the group.");
      }
      if (command.confirmTitle !== room.title) {
        throw new HttpsError("failed-precondition", "Group deletion confirmation did not match.");
      }
      const memberReferences = room.activeMemberIds.map((uid) => roomReference.collection("members").doc(uid));
      await Promise.all(memberReferences.map((reference) => transaction.get(reference)));
      const deletedAt = Timestamp.now();
      memberReferences.forEach((reference) => transaction.update(reference, {
        active: false,
        archived: false,
        leftAt: deletedAt,
        muted: false,
        pinned: false,
      }));
      transaction.update(roomReference, {
        activeMemberIds: [],
        deletedAt,
        ownerUid: null,
        revision: room.revision + 1,
        updatedAt: deletedAt,
      });
      createGroupAuditEvent(transaction, actorUid, command.roomId, "GROUP_DELETED", deletedAt);
    });
    return {deleted: true, roomId: command.roomId};
  },
);

async function updateGroupPresentation(
  actorUid: string,
  roomId: string,
  update: (
    transaction: Transaction,
    roomReference: DocumentReference,
    revision: number,
    changedAt: Timestamp,
  ) => void,
  eventType: string,
): Promise<number> {
  const roomReference = firebaseAdminFirestore.doc(`rooms/${roomId}`);
  let revision = 0;
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const {membership, room} = await requireActiveGroupActor(transaction, roomReference, actorUid);
    requireGroupAdministrator(membership.role);
    const changedAt = Timestamp.now();
    revision = room.revision + 1;
    update(transaction, roomReference, revision, changedAt);
    createGroupAuditEvent(transaction, actorUid, roomId, eventType, changedAt);
  });
  return revision;
}

function buildBlockReferencesForMemberSet(memberUids: string[]) {
  const paths = new Map<string, DocumentReference>();
  memberUids.forEach((firstUid, firstIndex) => {
    memberUids.slice(firstIndex + 1).forEach((secondUid) => {
      buildReciprocalBlockReferences(firstUid, secondUid).forEach((reference) => {
        paths.set(reference.path, reference);
      });
    });
  });
  return [...paths.values()];
}

function buildBlockReferencesBetween(newMemberUids: string[], activeMemberUids: string[]) {
  const newMemberSet = new Set(newMemberUids);
  const pairs = new Set<string>();
  const references = new Map<string, DocumentReference>();
  newMemberUids.forEach((newMemberUid) => {
    activeMemberUids.forEach((memberUid) => {
      if (newMemberUid === memberUid) return;
      const pairKey = [newMemberUid, memberUid].sort().join(":");
      if (pairs.has(pairKey) && newMemberSet.has(memberUid)) return;
      pairs.add(pairKey);
      buildReciprocalBlockReferences(newMemberUid, memberUid).forEach((reference) => {
        references.set(reference.path, reference);
      });
    });
  });
  return [...references.values()];
}

function createGroupAuditEvent(
  transaction: Transaction,
  actorUid: string,
  roomId: string,
  eventType: string,
  createdAt: Timestamp,
) {
  transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
    actorUid,
    createdAt,
    eventType,
    roomId,
  });
}
