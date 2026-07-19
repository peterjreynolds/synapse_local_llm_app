import {createHash} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {firebaseAdminFirestore} from "./firebaseAdmin.js";

export interface CallableRateLimitPolicy {
  maximumRequests: number;
  windowMillis: number;
}

export interface CallableRateLimitState {
  requestCount: number;
  windowStartedAtMillis: number;
}

export interface CallableRateLimitDecision {
  allowed: boolean;
  nextState: CallableRateLimitState;
  retryAfterMillis: number;
}

export const CALLABLE_RATE_LIMIT_POLICIES = {
  aiHostPolling: {maximumRequests: 12, windowMillis: 60_000},
  aiMutation: {maximumRequests: 60, windowMillis: 15 * 60_000},
  attachmentMutation: {maximumRequests: 60, windowMillis: 15 * 60_000},
  callMutation: {maximumRequests: 30, windowMillis: 60_000},
  callSignaling: {maximumRequests: 240, windowMillis: 60_000},
  cinderAvailabilityPolling: {maximumRequests: 6, windowMillis: 60_000},
  cinderSyncPolling: {maximumRequests: 120, windowMillis: 60_000},
  conversationMutation: {maximumRequests: 120, windowMillis: 60_000},
  groupMutation: {maximumRequests: 60, windowMillis: 15 * 60_000},
  invitationMutation: {maximumRequests: 10, windowMillis: 24 * 60 * 60_000},
  ownerMutation: {maximumRequests: 60, windowMillis: 60 * 60_000},
} as const satisfies Record<string, CallableRateLimitPolicy>;

export async function enforceCallableRateLimit(
  actorUid: string,
  bucket: keyof typeof CALLABLE_RATE_LIMIT_POLICIES,
): Promise<void> {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(actorUid)) {
    throw new HttpsError("unauthenticated", "An authenticated account is required.");
  }
  const policy = CALLABLE_RATE_LIMIT_POLICIES[bucket];
  const documentId = createHash("sha256").update(`${bucket}\u0000${actorUid}`, "utf8").digest("hex");
  const reference = firebaseAdminFirestore.doc(`callableRateLimits/${documentId}`);
  await firebaseAdminFirestore.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(reference);
    const now = Timestamp.now();
    const currentState = snapshot.exists ? readCallableRateLimitState(snapshot.data()) : null;
    const decision = resolveCallableRateLimitDecision(policy, currentState, now.toMillis());
    if (!decision.allowed) {
      throw new HttpsError("resource-exhausted", "Too many requests. Wait before trying again.");
    }
    transaction.set(reference, {
      bucket,
      requestCount: decision.nextState.requestCount,
      updatedAt: now,
      windowStartedAt: Timestamp.fromMillis(decision.nextState.windowStartedAtMillis),
    });
  });
}

export function resolveCallableRateLimitDecision(
  policy: CallableRateLimitPolicy,
  currentState: CallableRateLimitState | null,
  nowMillis: number,
): CallableRateLimitDecision {
  if (
    !Number.isSafeInteger(policy.maximumRequests) ||
    policy.maximumRequests < 1 ||
    !Number.isSafeInteger(policy.windowMillis) ||
    policy.windowMillis < 1 ||
    !Number.isSafeInteger(nowMillis) ||
    nowMillis < 0
  ) {
    throw new Error("Callable rate-limit policy is invalid.");
  }
  if (
    currentState === null ||
    nowMillis - currentState.windowStartedAtMillis >= policy.windowMillis
  ) {
    return {
      allowed: true,
      nextState: {requestCount: 1, windowStartedAtMillis: nowMillis},
      retryAfterMillis: 0,
    };
  }
  const retryAfterMillis = Math.max(
    1,
    currentState.windowStartedAtMillis + policy.windowMillis - nowMillis,
  );
  if (currentState.requestCount >= policy.maximumRequests) {
    return {allowed: false, nextState: currentState, retryAfterMillis};
  }
  return {
    allowed: true,
    nextState: {...currentState, requestCount: currentState.requestCount + 1},
    retryAfterMillis: 0,
  };
}

function readCallableRateLimitState(value: unknown): CallableRateLimitState {
  if (typeof value !== "object" || value === null || Array.isArray(value)) malformedRateLimitState();
  const record = value as Record<string, unknown>;
  const requestCount = record.requestCount;
  const windowStartedAt = record.windowStartedAt;
  if (
    typeof requestCount !== "number" ||
    !Number.isSafeInteger(requestCount) ||
    requestCount < 1 ||
    !(windowStartedAt instanceof Timestamp)
  ) {
    malformedRateLimitState();
  }
  return {requestCount, windowStartedAtMillis: windowStartedAt.toMillis()};
}

function malformedRateLimitState(): never {
  throw new HttpsError("data-loss", "Request throttling state is malformed.");
}
