import {Timestamp, type Query} from "firebase-admin/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {runRecordedOperationsJob} from "./operationsJobStatus.js";
import {
  OPERATIONAL_COLLECTION_GROUP_RETENTION_POLICIES,
  OPERATIONAL_RETENTION_POLICIES,
} from "./operationalRetentionPolicy.js";

const MAXIMUM_DELETES_PER_POLICY = 100;

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
  for (const policy of OPERATIONAL_COLLECTION_GROUP_RETENTION_POLICIES) {
    const cutoff = Timestamp.fromMillis(now.toMillis() - policy.retentionMillis);
    deletedDocumentCount += await deleteExpiredQuery(
      firebaseAdminFirestore.collectionGroup(policy.collectionName)
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
