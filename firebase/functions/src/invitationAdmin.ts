import {randomBytes, randomUUID} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {digestInvitationCode} from "./identity.js";
import {requireActiveOwner} from "./ownerAuthorization.js";

const MAXIMUM_INVITATION_USES = 100;
const MAXIMUM_INVITATION_LIFETIME_HOURS = 24 * 30;
const INVITATION_LABEL_LIMIT = 64;

interface CreateInvitationCommand {
  intendedLabel: string | null;
  lifetimeHours: number;
  maximumUses: number;
}

interface CreateInvitationReceipt {
  expiresAtMillis: number;
  invitationCode: string;
  invitationId: string;
  maximumUses: number;
}

interface OwnerInvitationSummary {
  expiresAtMillis: number;
  intendedLabel: string | null;
  invitationId: string;
  maximumUses: number;
  remainingUses: number;
  state: string;
}

export const listOwnerInvitations = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{invitations: OwnerInvitationSummary[]}> => {
    await requireActiveOwner(request.auth);
    const invitations = await firebaseAdminFirestore.collection("invitations")
      .orderBy("createdAt", "desc")
      .limit(100)
      .get();
    return {
      invitations: invitations.docs.map((invitation) => {
        const expiresAt = invitation.get("expiresAt");
        const maximumUses = invitation.get("maximumUses");
        const remainingUses = invitation.get("remainingUses");
        const state = invitation.get("state");
        const intendedLabel = invitation.get("intendedLabel");
        if (
          !(expiresAt instanceof Timestamp) ||
          typeof maximumUses !== "number" ||
          !Number.isSafeInteger(maximumUses) ||
          typeof remainingUses !== "number" ||
          !Number.isSafeInteger(remainingUses) ||
          typeof state !== "string"
        ) {
          throw new HttpsError("data-loss", "An invitation record is malformed.");
        }
        return {
          expiresAtMillis: expiresAt.toMillis(),
          intendedLabel: typeof intendedLabel === "string" ? intendedLabel : null,
          invitationId: invitation.id,
          maximumUses,
          remainingUses,
          state,
        };
      }),
    };
  },
);

export const createInvitation = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<CreateInvitationReceipt> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const command = parseCreateInvitationCommand(request.data);
    const invitationCode = randomBytes(32).toString("base64url");
    const invitationId = digestInvitationCode(invitationCode);
    const createdAt = Timestamp.now();
    const expiresAt = Timestamp.fromMillis(
      createdAt.toMillis() + command.lifetimeHours * 60 * 60 * 1_000,
    );
    const writes = firebaseAdminFirestore.batch();
    writes.create(firebaseAdminFirestore.doc(`invitations/${invitationId}`), {
      createdAt,
      creatorUid: ownerUid,
      expiresAt,
      intendedLabel: command.intendedLabel,
      maximumUses: command.maximumUses,
      redeemedCount: 0,
      remainingUses: command.maximumUses,
      revokedAt: null,
      state: "ACTIVE",
    });
    writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
      actorUid: ownerUid,
      createdAt,
      eventType: "INVITATION_CREATED",
      invitationId,
      maximumUses: command.maximumUses,
    });
    await writes.commit();
    return {
      expiresAtMillis: expiresAt.toMillis(),
      invitationCode,
      invitationId,
      maximumUses: command.maximumUses,
    };
  },
);

export const revokeInvitation = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{invitationId: string; state: "REVOKED"}> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const invitationId = parseInvitationId(request.data);
    const invitationReference = firebaseAdminFirestore.doc(`invitations/${invitationId}`);
    const revokedAt = Timestamp.now();
    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const invitation = await transaction.get(invitationReference);
      if (!invitation.exists) {
        throw new HttpsError("not-found", "Invitation was not found.");
      }
      if (invitation.get("state") === "REVOKED") {
        return;
      }
      transaction.update(invitationReference, {
        revokedAt,
        revokedBy: ownerUid,
        state: "REVOKED",
      });
      transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
        actorUid: ownerUid,
        createdAt: revokedAt,
        eventType: "INVITATION_REVOKED",
        invitationId,
      });
    });
    return {invitationId, state: "REVOKED"};
  },
);

export const setRegistrationApprovalRequired = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<{approvalRequired: boolean}> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const approvalRequired = parseApprovalRequired(request.data);
    const updatedAt = Timestamp.now();
    const writes = firebaseAdminFirestore.batch();
    writes.set(firebaseAdminFirestore.doc("systemSettings/registration"), {
      approvalRequired,
      updatedAt,
      updatedBy: ownerUid,
    });
    writes.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
      actorUid: ownerUid,
      approvalRequired,
      createdAt: updatedAt,
      eventType: "REGISTRATION_APPROVAL_POLICY_UPDATED",
    });
    await writes.commit();
    return {approvalRequired};
  },
);

export function parseCreateInvitationCommand(input: unknown): CreateInvitationCommand {
  if (!isRecord(input)) {
    throw new HttpsError("invalid-argument", "Invitation details are required.");
  }
  const maximumUses = input.maximumUses;
  const lifetimeHours = input.lifetimeHours;
  const intendedLabel = input.intendedLabel;
  if (
    typeof maximumUses !== "number" ||
    !Number.isSafeInteger(maximumUses) ||
    maximumUses < 1 ||
    maximumUses > MAXIMUM_INVITATION_USES
  ) {
    throw new HttpsError("invalid-argument", "Invitation use count is invalid.");
  }
  if (
    typeof lifetimeHours !== "number" ||
    !Number.isSafeInteger(lifetimeHours) ||
    lifetimeHours < 1 ||
    lifetimeHours > MAXIMUM_INVITATION_LIFETIME_HOURS
  ) {
    throw new HttpsError("invalid-argument", "Invitation lifetime is invalid.");
  }
  if (
    intendedLabel !== null &&
    (typeof intendedLabel !== "string" ||
      intendedLabel.trim().length === 0 ||
      intendedLabel.trim().length > INVITATION_LABEL_LIMIT)
  ) {
    throw new HttpsError("invalid-argument", "Invitation label is invalid.");
  }
  return {
    intendedLabel: typeof intendedLabel === "string" ? intendedLabel.trim() : null,
    lifetimeHours,
    maximumUses,
  };
}

function parseInvitationId(input: unknown): string {
  if (!isRecord(input) || typeof input.invitationId !== "string") {
    throw new HttpsError("invalid-argument", "Invitation identifier is invalid.");
  }
  const invitationId = input.invitationId.trim().toLocaleLowerCase("en-US");
  if (!/^[a-f0-9]{64}$/.test(invitationId)) {
    throw new HttpsError("invalid-argument", "Invitation identifier is invalid.");
  }
  return invitationId;
}

function parseApprovalRequired(input: unknown): boolean {
  if (!isRecord(input) || typeof input.approvalRequired !== "boolean") {
    throw new HttpsError("invalid-argument", "Approval policy is invalid.");
  }
  return input.approvalRequired;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
