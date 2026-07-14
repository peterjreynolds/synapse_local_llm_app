import {randomUUID} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {
  FIREBASE_FUNCTIONS_REGION,
  firebaseAdminAuth,
  firebaseAdminFirestore,
} from "./firebaseAdmin.js";
import {buildAccountClaims, type AccountState} from "./identity.js";
import {requireActiveOwner} from "./ownerAuthorization.js";

type RegistrationDecision = "APPROVE" | "REJECT";

interface ReviewRegistrationCommand {
  decision: RegistrationDecision;
  targetUid: string;
}

interface ReviewRegistrationReceipt {
  accountState: AccountState;
  targetUid: string;
}

export const reviewRegistration = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<ReviewRegistrationReceipt> => {
    const ownerUid = await requireActiveOwner(request.auth);
    const command = parseReviewRegistrationCommand(request.data);
    if (command.targetUid === ownerUid) {
      throw new HttpsError("failed-precondition", "The owner account cannot review itself.");
    }
    const accountState: AccountState =
      command.decision === "APPROVE" ? "ACTIVE" : "REJECTED";
    const targetProfileReference = firebaseAdminFirestore.doc(`profiles/${command.targetUid}`);
    const reviewedAt = Timestamp.now();

    await firebaseAdminFirestore.runTransaction(async (transaction) => {
      const targetProfile = await transaction.get(targetProfileReference);
      if (!targetProfile.exists) {
        throw new HttpsError("not-found", "Registration was not found.");
      }
      const currentState = targetProfile.get("accountState");
      if (currentState === accountState) {
        return;
      }
      if (currentState !== "PENDING_APPROVAL") {
        throw new HttpsError("failed-precondition", "Registration is no longer pending.");
      }
      transaction.update(targetProfileReference, {
        accountState,
        allowed: accountState === "ACTIVE",
        directoryVisible: accountState === "ACTIVE",
        reviewedAt,
        reviewedBy: ownerUid,
        role: "USER",
        updatedAt: reviewedAt,
      });
      transaction.create(firebaseAdminFirestore.doc(`securityAuditEvents/${randomUUID()}`), {
        actorUid: ownerUid,
        createdAt: reviewedAt,
        eventType: accountState === "ACTIVE" ? "REGISTRATION_APPROVED" : "REGISTRATION_REJECTED",
        targetUid: command.targetUid,
      });
    });

    const targetProfile = await targetProfileReference.get();
    const mustChangePassword = targetProfile.get("mustChangePassword") === true;
    try {
      await firebaseAdminAuth.setCustomUserClaims(
        command.targetUid,
        buildAccountClaims("USER", accountState, mustChangePassword),
      );
    } catch (error) {
      await firebaseAdminFirestore.doc(`registrationReviewFailures/${command.targetUid}`).set({
        accountState,
        recordedAt: Timestamp.now(),
        targetUid: command.targetUid,
      }).catch(() => undefined);
      throw error;
    }
    return {accountState, targetUid: command.targetUid};
  },
);

export function parseReviewRegistrationCommand(input: unknown): ReviewRegistrationCommand {
  if (!isRecord(input)) {
    throw new HttpsError("invalid-argument", "Registration review details are required.");
  }
  const targetUid = input.targetUid;
  const decision = input.decision;
  if (typeof targetUid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(targetUid)) {
    throw new HttpsError("invalid-argument", "Registration target is invalid.");
  }
  if (decision !== "APPROVE" && decision !== "REJECT") {
    throw new HttpsError("invalid-argument", "Registration decision is invalid.");
  }
  return {decision, targetUid};
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
