import {createHash} from "node:crypto";

export const ACCOUNT_DISPLAY_NAME_LIMIT = 64;
export const ACCOUNT_PASSWORD_MAXIMUM_LENGTH = 128;
export const ACCOUNT_PASSWORD_MINIMUM_LENGTH = 12;
export const INVITATION_CODE_MAXIMUM_LENGTH = 128;
export const INVITATION_CODE_MINIMUM_LENGTH = 32;

export type AccountRole = "OWNER" | "ADMIN" | "USER";
export type AccountState = "PENDING_APPROVAL" | "ACTIVE" | "REJECTED" | "DISABLED";

export interface AccountClaims {
  accountState: AccountState;
  claimsVersion: 1;
  mustChangePassword: boolean;
  role: AccountRole;
}

export interface InviteRegistrationCommand {
  displayName: string;
  invitationCode: string;
  password: string;
  usernameNormalized: string;
}

export function normalizeUsername(username: string): string {
  const normalized = username.normalize("NFKC").trim().toLocaleLowerCase("en-US");
  if (!/^[a-z][a-z0-9_]{2,31}$/.test(normalized)) {
    throw new Error("Username must contain 3-32 ASCII letters, digits, or underscores.");
  }
  return normalized;
}

export function buildSyntheticAccountEmail(username: string): string {
  return `${normalizeUsername(username)}@accounts.synapse.invalid`;
}

export function normalizeAccountDisplayName(displayName: string): string {
  const normalized = displayName.normalize("NFKC").trim();
  if (
    normalized.length === 0 ||
    normalized.length > ACCOUNT_DISPLAY_NAME_LIMIT ||
    /[\u0000-\u001f\u007f]/.test(normalized)
  ) {
    throw new Error(`Display name must contain 1-${ACCOUNT_DISPLAY_NAME_LIMIT} visible characters.`);
  }
  return normalized;
}

export function validateNewAccountPassword(password: string): string {
  if (
    password.length < ACCOUNT_PASSWORD_MINIMUM_LENGTH ||
    password.length > ACCOUNT_PASSWORD_MAXIMUM_LENGTH ||
    password.includes("\u0000")
  ) {
    throw new Error(
      `Password must contain ${ACCOUNT_PASSWORD_MINIMUM_LENGTH}-${ACCOUNT_PASSWORD_MAXIMUM_LENGTH} characters.`,
    );
  }
  return password;
}

export function normalizeInvitationCode(invitationCode: string): string {
  const normalized = invitationCode.trim();
  const invitationCodePattern = new RegExp(
    `^[A-Za-z0-9_-]{${INVITATION_CODE_MINIMUM_LENGTH},${INVITATION_CODE_MAXIMUM_LENGTH}}$`,
  );
  if (!invitationCodePattern.test(normalized)) {
    throw new Error("Invitation code is invalid.");
  }
  return normalized;
}

export function parseInviteRegistrationCommand(input: unknown): InviteRegistrationCommand {
  if (!isRecord(input)) {
    throw new Error("Registration details are required.");
  }
  const username = requireStringField(input, "username");
  const displayName = requireStringField(input, "displayName");
  const password = requireStringField(input, "password");
  const invitationCode = requireStringField(input, "invitationCode");
  return {
    displayName: normalizeAccountDisplayName(displayName),
    invitationCode: normalizeInvitationCode(invitationCode),
    password: validateNewAccountPassword(password),
    usernameNormalized: normalizeUsername(username),
  };
}

export function digestInvitationCode(invitationCode: string): string {
  return createHash("sha256").update(normalizeInvitationCode(invitationCode), "utf8").digest("hex");
}

export function buildRegistrationRateLimitId(subject: string): string {
  if (subject.trim().length === 0) {
    throw new Error("Registration rate-limit subject is required.");
  }
  return createHash("sha256").update(`registration:${subject}`, "utf8").digest("hex");
}

export function resolveInitialAccountState(approvalRequired: unknown): AccountState {
  return approvalRequired === false ? "ACTIVE" : "PENDING_APPROVAL";
}

export function buildAccountClaims(
  role: AccountRole,
  accountState: AccountState,
  mustChangePassword = false,
): AccountClaims {
  return {
    accountState,
    claimsVersion: 1,
    mustChangePassword,
    role,
  };
}

function requireStringField(record: Record<string, unknown>, fieldName: string): string {
  const field = record[fieldName];
  if (typeof field !== "string") {
    throw new Error(`${fieldName} must be a string.`);
  }
  return field;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
