import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {requireActiveOwner} from "./ownerAuthorization.js";

interface OwnerAuditEvent {
  actorUid: string;
  createdAtMillis: number;
  eventId: string;
  eventType: string;
  targetUid: string | null;
}

export const listOwnerAuditEvents = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{events: OwnerAuditEvent[]}> => {
    await requireActiveOwner(request.auth);
    const requestedLimit = parseAuditLimit(request.data);
    const events = await firebaseAdminFirestore.collection("securityAuditEvents")
      .orderBy("createdAt", "desc")
      .limit(requestedLimit)
      .get();
    return {
      events: events.docs.map((event) => {
        const actorUid = event.get("actorUid");
        const eventType = event.get("eventType");
        const createdAt = event.get("createdAt");
        const targetUid = event.get("targetUid");
        if (
          typeof actorUid !== "string" ||
          typeof eventType !== "string" ||
          !(createdAt instanceof Timestamp)
        ) {
          throw new HttpsError("data-loss", "An audit event is malformed.");
        }
        return {
          actorUid,
          createdAtMillis: createdAt.toMillis(),
          eventId: event.id,
          eventType,
          targetUid: typeof targetUid === "string" ? targetUid : null,
        };
      }),
    };
  },
);

function parseAuditLimit(input: unknown): number {
  if (input === undefined || input === null) return 50;
  if (!isRecord(input) || input.limit === undefined) return 50;
  if (
    typeof input.limit !== "number" ||
    !Number.isSafeInteger(input.limit) ||
    input.limit < 1 ||
    input.limit > 100
  ) {
    throw new HttpsError("invalid-argument", "Audit history limit is invalid.");
  }
  return input.limit;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
