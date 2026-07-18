import {randomUUID} from "node:crypto";
import {applicationDefault, getApps, initializeApp} from "firebase-admin/app";
import {FieldValue, getFirestore} from "firebase-admin/firestore";
import {buildDirectRoomIdentity} from "./domain.js";

const EXPECTED_PROJECT_ID = "synapse-chat-pjr-2026";
const REPAIR_AUTHORIZATION = "SYNAPSE_APPLY_LEGACY_DIRECT_ROOM_REPAIR";

export interface LegacyDirectRoomRepairPlan {
  memberIds: readonly [string, string];
  patch: {
    avatarObjectPath?: null;
    deletedAt?: null;
    ownerUid?: null;
    revision?: 1;
  };
}

export function buildLegacyDirectRoomRepairPlan(
  roomId: string,
  input: unknown,
): LegacyDirectRoomRepairPlan | null {
  if (!isRecord(input) || input.kind !== "DIRECT") return null;
  if (hasOwn(input, "deletedAt") && input.deletedAt !== null) return null;

  const memberIds = readUidPair(input.memberIds, "memberIds");
  const activeMemberIds = readUidPair(input.activeMemberIds, "activeMemberIds");
  const identity = buildDirectRoomIdentity(memberIds[0], memberIds[1]);
  if (
    identity.roomId !== roomId ||
    input.directKey !== identity.directKey ||
    !sameUidSet(memberIds, identity.memberIds) ||
    !sameUidSet(activeMemberIds, identity.memberIds)
  ) {
    throw new Error(`Direct room ${roomId} has inconsistent identity fields.`);
  }

  const patch: LegacyDirectRoomRepairPlan["patch"] = {};
  if (!hasOwn(input, "avatarObjectPath")) {
    patch.avatarObjectPath = null;
  } else if (input.avatarObjectPath !== null && typeof input.avatarObjectPath !== "string") {
    throw new Error(`Direct room ${roomId} has an invalid avatar object path.`);
  }
  if (!hasOwn(input, "deletedAt")) patch.deletedAt = null;
  if (!hasOwn(input, "ownerUid")) {
    patch.ownerUid = null;
  } else if (input.ownerUid !== null) {
    throw new Error(`Direct room ${roomId} has an invalid owner.`);
  }
  if (!hasOwn(input, "revision")) {
    patch.revision = 1;
  } else if (
    typeof input.revision !== "number" ||
    !Number.isSafeInteger(input.revision) ||
    input.revision < 1
  ) {
    throw new Error(`Direct room ${roomId} has an invalid revision.`);
  }

  return Object.keys(patch).length === 0 ? null : {memberIds: identity.memberIds, patch};
}

export function assertActiveDirectRoomMembership(
  roomId: string,
  membershipId: string,
  input: unknown,
): void {
  if (
    !isRecord(input) ||
    input.active !== true ||
    input.role !== "MEMBER" ||
    input.uid !== membershipId
  ) {
    throw new Error(`Direct room ${roomId} has an invalid active membership.`);
  }
}

async function applyRepair(): Promise<number> {
  const firestore = getFirestore();
  const repairId = randomUUID();
  const rooms = await firestore.collection("rooms").get();
  let repairedRoomCount = 0;

  for (const listedRoom of rooms.docs) {
    const listedPlan = buildLegacyDirectRoomRepairPlan(listedRoom.id, listedRoom.data());
    if (listedPlan === null) continue;

    const repaired = await firestore.runTransaction(async (transaction): Promise<boolean> => {
      const roomReference = listedRoom.ref;
      const receiptReference = firestore.doc(`roomSchemaRepairReceipts/${listedRoom.id}`);
      const roomSnapshot = await transaction.get(roomReference);
      if (!roomSnapshot.exists) return false;
      const plan = buildLegacyDirectRoomRepairPlan(roomSnapshot.id, roomSnapshot.data());
      const receiptSnapshot = await transaction.get(receiptReference);
      if (plan === null) return false;
      if (receiptSnapshot.exists) {
        throw new Error(`Direct room ${roomSnapshot.id} still needs repair after a repair receipt was recorded.`);
      }
      const membershipSnapshots = await Promise.all(
        plan.memberIds.map((uid) => transaction.get(roomReference.collection("members").doc(uid))),
      );
      membershipSnapshots.forEach((membership, index) => {
        const membershipId = plan.memberIds[index];
        if (!membershipId || !membership.exists) {
          throw new Error(`Direct room ${roomSnapshot.id} is missing an active membership.`);
        }
        assertActiveDirectRoomMembership(roomSnapshot.id, membershipId, membership.data());
      });

      transaction.update(roomReference, plan.patch);
      transaction.create(receiptReference, {
        addedFields: Object.keys(plan.patch).sort(),
        appliedAt: FieldValue.serverTimestamp(),
        repairId,
        roomId: roomSnapshot.id,
        source: "legacy-direct-room-repair-command",
      });
      return true;
    });
    if (repaired) repairedRoomCount += 1;
  }

  await firestore.doc(`roomSchemaRepairs/${repairId}`).create({
    appliedAt: FieldValue.serverTimestamp(),
    repairId,
    repairedRoomCount,
    scannedRoomCount: rooms.size,
    source: "legacy-direct-room-repair-command",
  });
  return repairedRoomCount;
}

function readUidPair(value: unknown, fieldName: string): [string, string] {
  if (
    !Array.isArray(value) ||
    value.length !== 2 ||
    value.some((uid) => typeof uid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) ||
    value[0] === value[1]
  ) {
    throw new Error(`Direct room ${fieldName} is invalid.`);
  }
  return [value[0] as string, value[1] as string];
}

function sameUidSet(first: readonly string[], second: readonly string[]): boolean {
  return first.length === second.length && first.every((uid) => second.includes(uid));
}

function hasOwn(input: Record<string, unknown>, fieldName: string): boolean {
  return Object.prototype.hasOwnProperty.call(input, fieldName);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function main(): Promise<void> {
  const projectId = process.env.GOOGLE_CLOUD_PROJECT ?? EXPECTED_PROJECT_ID;
  if (projectId !== EXPECTED_PROJECT_ID) {
    throw new Error(`Refusing direct-room repair against unexpected project ${projectId}.`);
  }
  if (process.env[REPAIR_AUTHORIZATION] !== "1") {
    throw new Error(`Set ${REPAIR_AUTHORIZATION}=1 to authorize the direct-room repair.`);
  }
  if (getApps().length === 0) {
    initializeApp({credential: applicationDefault(), projectId});
  }
  const repairedRoomCount = await applyRepair();
  process.stdout.write(`Repaired ${repairedRoomCount} legacy direct room(s).\n`);
}

if (require.main === module) {
  void main().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : "Unknown direct-room repair failure.";
    process.stderr.write(`Direct-room repair failed: ${message}\n`);
    process.exitCode = 1;
  });
}
