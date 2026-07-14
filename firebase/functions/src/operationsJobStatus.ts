import {Timestamp} from "firebase-admin/firestore";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export type OperationsJobId = "attachmentCleanup" | "operationalDataCleanup";

export interface OperationsJobStatusSummary {
  affectedDocumentCount: number | null;
  lastCompletedAtMillis: number | null;
  lastStartedAtMillis: number | null;
  state: "FAILED" | "NEVER_RUN" | "RUNNING" | "SUCCEEDED";
}

export async function runRecordedOperationsJob(
  jobId: OperationsJobId,
  work: () => Promise<number>,
): Promise<void> {
  const statusReference = firebaseAdminFirestore.doc(`operationsJobStatus/${jobId}`);
  const startedAt = Timestamp.now();
  await statusReference.set({
    affectedDocumentCount: null,
    failureCode: null,
    jobId,
    lastStartedAt: startedAt,
    state: "RUNNING",
    updatedAt: startedAt,
  }, {merge: true});
  try {
    const affectedDocumentCount = await work();
    const completedAt = Timestamp.now();
    await statusReference.set({
      affectedDocumentCount,
      failureCode: null,
      jobId,
      lastCompletedAt: completedAt,
      state: "SUCCEEDED",
      updatedAt: completedAt,
    }, {merge: true});
  } catch (error) {
    const completedAt = Timestamp.now();
    await statusReference.set({
      affectedDocumentCount: null,
      failureCode: safeFailureCode(error),
      jobId,
      lastCompletedAt: completedAt,
      state: "FAILED",
      updatedAt: completedAt,
    }, {merge: true}).catch(() => undefined);
    throw error;
  }
}

export function readOperationsJobStatus(input: unknown): OperationsJobStatusSummary {
  if (input === undefined) return neverRunStatus();
  if (!isRecord(input)) return failedStatus();
  const state = input.state;
  if (state !== "RUNNING" && state !== "SUCCEEDED" && state !== "FAILED") {
    return failedStatus();
  }
  const affectedDocumentCount = readOptionalCount(input.affectedDocumentCount);
  const lastCompletedAtMillis = readOptionalTimestampMillis(input.lastCompletedAt);
  const lastStartedAtMillis = readOptionalTimestampMillis(input.lastStartedAt);
  if (
    affectedDocumentCount === INVALID_NUMBER ||
    lastCompletedAtMillis === INVALID_NUMBER ||
    lastStartedAtMillis === INVALID_NUMBER
  ) {
    return failedStatus();
  }
  return {
    affectedDocumentCount,
    lastCompletedAtMillis,
    lastStartedAtMillis,
    state,
  };
}

function neverRunStatus(): OperationsJobStatusSummary {
  return {
    affectedDocumentCount: null,
    lastCompletedAtMillis: null,
    lastStartedAtMillis: null,
    state: "NEVER_RUN",
  };
}

function failedStatus(): OperationsJobStatusSummary {
  return {
    affectedDocumentCount: null,
    lastCompletedAtMillis: null,
    lastStartedAtMillis: null,
    state: "FAILED",
  };
}

const INVALID_NUMBER = Symbol("INVALID_NUMBER");

function readOptionalCount(value: unknown): number | null | typeof INVALID_NUMBER {
  if (value === undefined || value === null) return null;
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : INVALID_NUMBER;
}

function readOptionalTimestampMillis(value: unknown): number | null | typeof INVALID_NUMBER {
  if (value === undefined || value === null) return null;
  return value instanceof Timestamp ? value.toMillis() : INVALID_NUMBER;
}

function safeFailureCode(error: unknown): string {
  const candidate = error instanceof Error ? error.name : "UNKNOWN_FAILURE";
  return /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(candidate) ? candidate : "UNKNOWN_FAILURE";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
