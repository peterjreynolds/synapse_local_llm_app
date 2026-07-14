import {Timestamp, type Query} from "firebase-admin/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {runRecordedOperationsJob} from "./operationsJobStatus.js";

interface RetentionPolicy {
  collectionName: string;
  retentionMillis: number;
  timestampField: string;
}

const DAY_MILLIS = 24 * 60 * 60 * 1_000;
const MAXIMUM_DELETES_PER_POLICY = 100;

export const OPERATIONAL_RETENTION_POLICIES = [
  {collectionName: "callableRateLimits", retentionMillis: 2 * DAY_MILLIS, timestampField: "windowStartedAt"},
  {collectionName: "registrationRateLimits", retentionMillis: 2 * DAY_MILLIS, timestampField: "windowStartedAt"},
  {collectionName: "registrationReservations", retentionMillis: 30 * DAY_MILLIS, timestampField: "createdAt"},
  {collectionName: "invitations", retentionMillis: 30 * DAY_MILLIS, timestampField: "expiresAt"},
  {collectionName: "notificationDeliveries", retentionMillis: 30 * DAY_MILLIS, timestampField: "startedAt"},
  {collectionName: "remoteAiAuditEvents", retentionMillis: 90 * DAY_MILLIS, timestampField: "createdAt"},
  {collectionName: "remoteAiResponseAudits", retentionMillis: 90 * DAY_MILLIS, timestampField: "completedAt"},
  {collectionName: "inviteRedemptions", retentionMillis: 180 * DAY_MILLIS, timestampField: "redeemedAt"},
  {collectionName: "securityAuditEvents", retentionMillis: 365 * DAY_MILLIS, timestampField: "createdAt"},
] as const satisfies readonly RetentionPolicy[];

export const cleanupExpiredOperationalData = onSchedule(
  {
    region: FIREBASE_FUNCTIONS_REGION,
    retryCount: 3,
    schedule: "every 24 hours",
  },
  async (): Promise<void> => {
    await runRecordedOperationsJob("operationalDataCleanup", cleanupRetainedOperationalData);
  },
);

export async function cleanupRetainedOperationalData(now = Timestamp.now()): Promise<number> {
  let deletedDocumentCount = await deleteExpiredQuery(
    firebaseAdminFirestore.collectionGroup("typing")
      .where("expiresAt", "<=", now)
      .limit(MAXIMUM_DELETES_PER_POLICY),
  );
  for (const policy of OPERATIONAL_RETENTION_POLICIES) {
    const cutoff = Timestamp.fromMillis(now.toMillis() - policy.retentionMillis);
    deletedDocumentCount += await deleteExpiredQuery(
      firebaseAdminFirestore.collection(policy.collectionName)
        .where(policy.timestampField, "<=", cutoff)
        .limit(MAXIMUM_DELETES_PER_POLICY),
    );
  }
  return deletedDocumentCount;
}

async function deleteExpiredQuery(query: Query): Promise<number> {
  const snapshots = await query.get();
  if (snapshots.empty) return 0;
  const writes = firebaseAdminFirestore.batch();
  snapshots.docs.forEach((snapshot) => writes.delete(snapshot.ref));
  await writes.commit();
  return snapshots.size;
}
