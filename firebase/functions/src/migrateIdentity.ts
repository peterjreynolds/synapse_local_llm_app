import {randomUUID} from "node:crypto";
import {applicationDefault, getApps, initializeApp} from "firebase-admin/app";
import {getAuth, type UserRecord} from "firebase-admin/auth";
import {FieldValue, getFirestore} from "firebase-admin/firestore";
import {
  buildAccountClaims,
  normalizeUsername,
  type AccountRole,
  type AccountState,
} from "./identity.js";

const EXPECTED_PROJECT_ID = "synapse-chat-pjr-2026";
const MIGRATION_AUTHORIZATION = "SYNAPSE_APPLY_IDENTITY_MIGRATION";
const SYNTHETIC_ACCOUNT_DOMAIN = "@accounts.synapse.invalid";
const OWNER_ACCOUNT_EMAIL = `peter${SYNTHETIC_ACCOUNT_DOMAIN}`;

export interface ExistingIdentityInput {
  disabled: boolean;
  email: string | null;
  profileAccountState: unknown;
  profileAllowed: unknown;
  profileExists: boolean;
  profileMustChangePassword: unknown;
  profileUsernameNormalized: unknown;
  uid: string;
}

export interface IdentityMigrationAccount {
  accountState: AccountState;
  mustChangePassword: boolean;
  role: AccountRole;
  uid: string;
  usernameNormalized: string;
}

export function buildIdentityMigrationAccount(
  input: ExistingIdentityInput,
): IdentityMigrationAccount | null {
  const email = input.email?.trim().toLocaleLowerCase("en-US") ?? null;
  if (!email?.endsWith(SYNTHETIC_ACCOUNT_DOMAIN)) {
    return null;
  }
  if (!input.profileExists) {
    throw new Error(`Account ${input.uid} has no profile; refusing partial identity migration.`);
  }
  const emailUsername = email.slice(0, -SYNTHETIC_ACCOUNT_DOMAIN.length);
  const usernameNormalized = normalizeUsername(emailUsername);
  if (
    typeof input.profileUsernameNormalized !== "string" ||
    normalizeUsername(input.profileUsernameNormalized) !== usernameNormalized
  ) {
    throw new Error(`Account ${input.uid} has inconsistent username identity.`);
  }
  const existingAccountState = isAccountState(input.profileAccountState)
    ? input.profileAccountState
    : null;
  if (
    existingAccountState !== null &&
    ((existingAccountState === "ACTIVE") !== (input.profileAllowed === true))
  ) {
    throw new Error(`Account ${input.uid} has inconsistent profile access state.`);
  }
  const accountState = input.disabled
    ? "DISABLED"
    : existingAccountState ?? (input.profileAllowed === true ? "ACTIVE" : "DISABLED");
  return {
    accountState,
    mustChangePassword: input.profileMustChangePassword === true,
    role: email === OWNER_ACCOUNT_EMAIL ? "OWNER" : "USER",
    uid: input.uid,
    usernameNormalized,
  };
}

async function listAllUsers(): Promise<UserRecord[]> {
  const auth = getAuth();
  const users: UserRecord[] = [];
  let pageToken: string | undefined;
  do {
    const page = await auth.listUsers(1_000, pageToken);
    users.push(...page.users);
    pageToken = page.pageToken;
  } while (pageToken);
  return users;
}

async function buildMigrationPlan(users: readonly UserRecord[]): Promise<IdentityMigrationAccount[]> {
  const firestore = getFirestore();
  const accountUsers = users.filter((user) =>
    user.email?.toLocaleLowerCase("en-US").endsWith(SYNTHETIC_ACCOUNT_DOMAIN) === true
  );
  if (accountUsers.length === 0) {
    throw new Error("No Synapse accounts were found; refusing identity migration.");
  }
  const profileSnapshots = await firestore.getAll(
    ...accountUsers.map((user) => firestore.doc(`profiles/${user.uid}`)),
  );
  const migrationAccounts = accountUsers.map((user, index) => {
    const profile = profileSnapshots[index];
    if (!profile) {
      throw new Error(`Profile lookup failed for account ${user.uid}.`);
    }
    return buildIdentityMigrationAccount({
      disabled: user.disabled,
      email: user.email ?? null,
      profileAccountState: profile.get("accountState"),
      profileAllowed: profile.get("allowed"),
      profileExists: profile.exists,
      profileMustChangePassword: profile.get("mustChangePassword"),
      profileUsernameNormalized: profile.get("usernameNormalized"),
      uid: user.uid,
    });
  }).filter((account): account is IdentityMigrationAccount => account !== null);

  if (!migrationAccounts.some((account) => account.role === "OWNER")) {
    throw new Error("Peter's owner account is missing; refusing identity migration.");
  }
  if (migrationAccounts.filter((account) => account.role === "OWNER").length !== 1) {
    throw new Error("Identity migration requires exactly one owner account.");
  }
  return migrationAccounts;
}

async function applyMigration(accounts: readonly IdentityMigrationAccount[]): Promise<void> {
  const auth = getAuth();
  const firestore = getFirestore();
  const migrationId = randomUUID();

  for (const account of accounts) {
    await auth.setCustomUserClaims(
      account.uid,
      buildAccountClaims(account.role, account.accountState, account.mustChangePassword),
    );
  }

  const writer = firestore.bulkWriter();
  for (const account of accounts) {
    writer.set(
      firestore.doc(`profiles/${account.uid}`),
      {
        accountState: account.accountState,
        allowed: account.accountState === "ACTIVE",
        mustChangePassword: account.mustChangePassword,
        role: account.role,
        updatedAt: FieldValue.serverTimestamp(),
      },
      {merge: true},
    );
    writer.set(
      firestore.doc(`usernames/${account.usernameNormalized}`),
      {
        state: "CLAIMED",
        uid: account.uid,
        usernameNormalized: account.usernameNormalized,
      },
      {merge: true},
    );
    writer.set(
      firestore.doc(`identityMigrationReceipts/${account.uid}`),
      {
        accountState: account.accountState,
        appliedAt: FieldValue.serverTimestamp(),
        migrationId,
        role: account.role,
        uid: account.uid,
      },
      {merge: false},
    );
  }

  const rooms = await firestore.collection("rooms").get();
  for (const room of rooms.docs) {
    const activeMemberships = await room.ref.collection("members")
      .where("active", "==", true)
      .get();
    const activeMemberIds = activeMemberships.docs
      .map((membership) => membership.id)
      .sort();
    writer.set(room.ref, {activeMemberIds}, {merge: true});
  }
  await writer.close();

  await firestore.doc(`identityMigrations/${migrationId}`).create({
    accountCount: accounts.length,
    appliedAt: FieldValue.serverTimestamp(),
    migrationId,
    roomCount: rooms.size,
    source: "identity-migration-command",
  });
}

function isAccountState(value: unknown): value is AccountState {
  return value === "PENDING_APPROVAL" ||
    value === "ACTIVE" ||
    value === "REJECTED" ||
    value === "DISABLED";
}

async function main(): Promise<void> {
  const projectId = process.env.GOOGLE_CLOUD_PROJECT ?? EXPECTED_PROJECT_ID;
  if (projectId !== EXPECTED_PROJECT_ID) {
    throw new Error(`Refusing identity migration against unexpected project ${projectId}.`);
  }
  if (process.env[MIGRATION_AUTHORIZATION] !== "1") {
    throw new Error(`Set ${MIGRATION_AUTHORIZATION}=1 to authorize the identity migration.`);
  }
  if (getApps().length === 0) {
    initializeApp({credential: applicationDefault(), projectId});
  }
  const accounts = await buildMigrationPlan(await listAllUsers());
  await applyMigration(accounts);
  process.stdout.write(`Migrated ${accounts.length} account identities.\n`);
}

if (require.main === module) {
  void main().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : "Unknown identity migration failure.";
    process.stderr.write(`Identity migration failed: ${message}\n`);
    process.exitCode = 1;
  });
}
