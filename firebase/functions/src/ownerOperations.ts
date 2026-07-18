import {Timestamp, type DocumentSnapshot} from "firebase-admin/firestore";
import {onCall} from "firebase-functions/v2/https";
import {enforceCallableRateLimit} from "./callableRateLimit.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  readOperationsJobStatus,
  type OperationsJobStatusSummary,
} from "./operationsJobStatus.js";
import {requireActiveOwner} from "./ownerAuthorization.js";

const MAXIMUM_INTEGRITY_ROOM_SAMPLE = 25;
const MAXIMUM_ROOM_MEMBERS = 20;
const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

interface OwnerOperationsSummary {
  activeDeviceCount: number;
  activeRoomCount: number;
  attachmentCleanup: OperationsJobStatusSummary;
  backendRevision: string;
  backendState: "HEALTHY";
  failedNotificationDeliveryCount: number;
  generatedAtMillis: number;
  integrity: {
    checkedRoomCount: number;
    issueCodes: string[];
    issueCount: number;
    sampleLimit: number;
    sampleLimitReached: boolean;
  };
  operationalDataCleanup: OperationsJobStatusSummary;
  pendingNotificationDeliveryCount: number;
  totalDeviceCount: number;
}

export const getOwnerOperationsSummary = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<OwnerOperationsSummary> => {
    const ownerUid = await requireActiveOwner(request.auth);
    await enforceCallableRateLimit(ownerUid, "ownerMutation");
    const [
      totalDevices,
      activeDevices,
      pendingNotificationDeliveries,
      failedNotificationDeliveries,
      activeRooms,
      sampledRooms,
      attachmentCleanupSnapshot,
      operationalDataCleanupSnapshot,
    ] = await Promise.all([
      firebaseAdminFirestore.collection("devices").count().get(),
      firebaseAdminFirestore.collection("devices").where("active", "==", true).count().get(),
      firebaseAdminFirestore.collection("notificationDeliveries")
        .where("state", "==", "PROCESSING").count().get(),
      firebaseAdminFirestore.collection("notificationDeliveries")
        .where("failureCount", ">", 0).count().get(),
      firebaseAdminFirestore.collection("rooms").where("deletedAt", "==", null).count().get(),
      firebaseAdminFirestore.collection("rooms")
        .where("deletedAt", "==", null)
        .limit(MAXIMUM_INTEGRITY_ROOM_SAMPLE)
        .get(),
      firebaseAdminFirestore.doc("operationsJobStatus/attachmentCleanup").get(),
      firebaseAdminFirestore.doc("operationsJobStatus/operationalDataCleanup").get(),
    ]);
    const integrity = await diagnoseRoomSnapshotIntegrity(sampledRooms.docs);
    return {
      activeDeviceCount: activeDevices.data().count,
      activeRoomCount: activeRooms.data().count,
      attachmentCleanup: readOperationsJobStatus(
        attachmentCleanupSnapshot.exists ? attachmentCleanupSnapshot.data() : undefined,
      ),
      backendRevision: readBackendRevision(),
      backendState: "HEALTHY",
      failedNotificationDeliveryCount: failedNotificationDeliveries.data().count,
      generatedAtMillis: Timestamp.now().toMillis(),
      integrity: {
        ...integrity,
        sampleLimit: MAXIMUM_INTEGRITY_ROOM_SAMPLE,
        sampleLimitReached: sampledRooms.size === MAXIMUM_INTEGRITY_ROOM_SAMPLE,
      },
      operationalDataCleanup: readOperationsJobStatus(
        operationalDataCleanupSnapshot.exists ? operationalDataCleanupSnapshot.data() : undefined,
      ),
      pendingNotificationDeliveryCount: pendingNotificationDeliveries.data().count,
      totalDeviceCount: totalDevices.data().count,
    };
  },
);

async function diagnoseRoomSnapshotIntegrity(roomSnapshots: DocumentSnapshot[]): Promise<{
  checkedRoomCount: number;
  issueCodes: string[];
  issueCount: number;
}> {
  const membershipEntries = roomSnapshots.flatMap((roomSnapshot) => {
    const activeMemberIds = readUidList(roomSnapshot.get("activeMemberIds")) ?? [];
    return activeMemberIds.map((uid) => ({
      reference: roomSnapshot.ref.collection("members").doc(uid),
      roomPath: roomSnapshot.ref.path,
      uid,
    }));
  });
  const membershipSnapshots = membershipEntries.length === 0 ? [] :
    await firebaseAdminFirestore.getAll(...membershipEntries.map((entry) => entry.reference));
  const membershipsByRoom = new Map<string, Map<string, unknown>>();
  membershipEntries.forEach((entry, index) => {
    const roomMemberships = membershipsByRoom.get(entry.roomPath) ?? new Map<string, unknown>();
    const membershipSnapshot = membershipSnapshots[index];
    roomMemberships.set(entry.uid, membershipSnapshot?.exists ? membershipSnapshot.data() : undefined);
    membershipsByRoom.set(entry.roomPath, roomMemberships);
  });

  let issueCount = 0;
  const issueCodes = new Set<string>();
  roomSnapshots.forEach((roomSnapshot) => {
    const issues = diagnoseActiveRoomIntegrity(
      roomSnapshot.data(),
      membershipsByRoom.get(roomSnapshot.ref.path) ?? new Map<string, unknown>(),
    );
    issueCount += issues.length;
    issues.forEach((issue) => issueCodes.add(issue));
  });
  return {
    checkedRoomCount: roomSnapshots.length,
    issueCodes: [...issueCodes].sort(),
    issueCount,
  };
}

export function diagnoseActiveRoomIntegrity(
  input: unknown,
  membershipsByUid: ReadonlyMap<string, unknown>,
): string[] {
  if (!isRecord(input)) return ["ROOM_DOCUMENT_MALFORMED"];
  const issues = new Set<string>();
  const memberIds = readUidList(input.memberIds);
  const activeMemberIds = readUidList(input.activeMemberIds);
  if (memberIds === null) issues.add("MEMBER_IDS_MALFORMED");
  if (activeMemberIds === null || activeMemberIds.length === 0) {
    issues.add("ACTIVE_MEMBER_IDS_MALFORMED");
  }
  if (activeMemberIds && activeMemberIds.length > MAXIMUM_ROOM_MEMBERS) {
    issues.add("ACTIVE_MEMBER_LIMIT_EXCEEDED");
  }
  if (memberIds && activeMemberIds?.some((uid) => !memberIds.includes(uid))) {
    issues.add("ACTIVE_MEMBER_NOT_IN_HISTORY");
  }
  if (input.kind !== "DIRECT" && input.kind !== "GROUP") {
    issues.add("ROOM_KIND_INVALID");
  }
  if (input.kind === "DIRECT" && (memberIds?.length !== 2 || activeMemberIds?.length !== 2)) {
    issues.add("DIRECT_MEMBER_COUNT_INVALID");
  }
  activeMemberIds?.forEach((uid) => {
    const membership = membershipsByUid.get(uid);
    if (!isRecord(membership)) {
      issues.add("ACTIVE_MEMBERSHIP_MISSING");
      return;
    }
    if (membership.uid !== uid) issues.add("MEMBERSHIP_UID_MISMATCH");
    if (membership.active !== true) issues.add("ACTIVE_MEMBERSHIP_INACTIVE");
    if (
      membership.role !== "OWNER" &&
      membership.role !== "ADMIN" &&
      membership.role !== "MEMBER"
    ) {
      issues.add("MEMBERSHIP_ROLE_INVALID");
    }
    if (input.kind === "DIRECT" && membership.role !== "MEMBER") {
      issues.add("DIRECT_MEMBERSHIP_ROLE_INVALID");
    }
  });
  if (input.kind === "GROUP") {
    const ownerUid = input.ownerUid;
    if (typeof ownerUid !== "string" || !activeMemberIds?.includes(ownerUid)) {
      issues.add("GROUP_OWNER_NOT_ACTIVE");
    } else {
      const ownerMembership = membershipsByUid.get(ownerUid);
      if (!isRecord(ownerMembership) || ownerMembership.role !== "OWNER") {
        issues.add("GROUP_OWNER_ROLE_INVALID");
      }
    }
  }
  return [...issues].sort();
}

function readUidList(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.some((uid) => typeof uid !== "string" || !UID_PATTERN.test(uid))) {
    return null;
  }
  const uniqueUids = [...new Set(value)];
  return uniqueUids.length === value.length ? uniqueUids : null;
}

function readBackendRevision(): string {
  const candidate = process.env.K_REVISION;
  if (typeof candidate === "string" && /^[A-Za-z0-9._-]{1,128}$/.test(candidate)) return candidate;
  return process.env.FUNCTIONS_EMULATOR === "true" ? "local-emulator" : "unavailable";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
