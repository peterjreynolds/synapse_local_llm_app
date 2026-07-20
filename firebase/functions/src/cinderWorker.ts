import {HttpsError, onRequest} from "firebase-functions/v2/https";
import {defineSecret} from "firebase-functions/params";
import {completeCinderResponse} from "./cinderCompletion.js";
import {sendCinderOutbound} from "./cinderOutbound.js";
import {
  CINDER_WORKER_PROTOCOL_VERSION,
  parseCinderWorkerClaimCommand,
  parseCompleteCinderResponseCommand,
  parseFailCinderResponseCommand,
  parseSendCinderOutboundMessageCommand,
  parseSkipCinderResponseCommand,
} from "./cinderDomain.js";
import {
  claimNextCinderResponse,
  failCinderResponse,
  skipCinderResponse,
} from "./cinderLease.js";
import {
  CinderWorkerAuthorizationVerifier,
  createCinderWorkerAuthorizationVerifier,
} from "./cinderWorkerAuthentication.js";
import {FIREBASE_FUNCTIONS_REGION} from "./firebaseAdmin.js";

type CinderWorkerOperationName = "claim" | "complete" | "fail" | "send" | "skip";

interface CinderWorkerBoundaryRequest {
  authorizationHeader: string | undefined;
  body: unknown;
  method: string;
}

interface CinderWorkerBoundaryResponse {
  body: unknown;
  status: number;
}

type CinderWorkerOperation = (body: unknown) => Promise<unknown>;

const CINDER_WORKER_TOKEN = defineSecret("CINDER_WORKER_TOKEN");

export const claimCinderResponse = cinderWorkerEndpoint("claim");
export const completeCinderResponseJob = cinderWorkerEndpoint("complete");
export const failCinderResponseJob = cinderWorkerEndpoint("fail");
export const sendCinderOutboundMessage = cinderWorkerEndpoint("send");
export const skipCinderResponseJob = cinderWorkerEndpoint("skip");

export async function handleCinderWorkerBoundaryRequest(
  request: CinderWorkerBoundaryRequest,
  verifyAuthorization: CinderWorkerAuthorizationVerifier,
  operation: CinderWorkerOperation,
): Promise<CinderWorkerBoundaryResponse> {
  if (request.method !== "POST") {
    return {body: {error: "METHOD_NOT_ALLOWED"}, status: 405};
  }
  if (!verifyAuthorization(request.authorizationHeader)) {
    return {body: {error: "UNAUTHORIZED"}, status: 401};
  }
  try {
    const result = await operation(request.body);
    return {
      body: {protocolVersion: CINDER_WORKER_PROTOCOL_VERSION, result},
      status: 200,
    };
  } catch (error) {
    return {
      body: {error: publicWorkerErrorCode(error)},
      status: workerErrorStatus(error),
    };
  }
}

function cinderWorkerEndpoint(operationName: CinderWorkerOperationName) {
  return onRequest(
    {
      region: FIREBASE_FUNCTIONS_REGION,
      secrets: [CINDER_WORKER_TOKEN],
      timeoutSeconds: 30,
    },
    async (request, response): Promise<void> => {
      const boundaryResponse = await handleCinderWorkerBoundaryRequest(
        {
          authorizationHeader: request.get("authorization"),
          body: request.body,
          method: request.method,
        },
        createCinderWorkerAuthorizationVerifier(CINDER_WORKER_TOKEN.value()),
        operationForName(operationName),
      );
      response.status(boundaryResponse.status).json(boundaryResponse.body);
    },
  );
}

function operationForName(operationName: CinderWorkerOperationName): CinderWorkerOperation {
  switch (operationName) {
  case "claim":
    return async (body) => {
      const command = parseCinderWorkerClaimCommand(body);
      return claimNextCinderResponse(command.workerId);
    };
  case "complete":
    return async (body) => completeCinderResponse(parseCompleteCinderResponseCommand(body));
  case "fail":
    return async (body) => failCinderResponse(parseFailCinderResponseCommand(body));
  case "send":
    return async (body) => sendCinderOutbound(parseSendCinderOutboundMessageCommand(body));
  case "skip":
    return async (body) => skipCinderResponse(parseSkipCinderResponseCommand(body));
  }
}

function workerErrorStatus(error: unknown): number {
  if (!(error instanceof HttpsError)) return 500;
  switch (error.code) {
  case "invalid-argument": return 400;
  case "already-exists":
  case "aborted":
  case "failed-precondition": return 409;
  case "permission-denied": return 403;
  case "not-found": return 404;
  case "resource-exhausted": return 429;
  default: return 500;
  }
}

function publicWorkerErrorCode(error: unknown): string {
  if (!(error instanceof HttpsError)) return "INTERNAL";
  switch (error.code) {
  case "invalid-argument": return "INVALID_COMMAND";
  case "already-exists": return "IDEMPOTENCY_CONFLICT";
  case "aborted": return "CONFLICT";
  case "failed-precondition": return "LEASE_UNAVAILABLE";
  case "permission-denied": return "FORBIDDEN";
  case "not-found": return "TARGET_UNAVAILABLE";
  case "resource-exhausted": return "RATE_LIMITED";
  default: return "INTERNAL";
  }
}
