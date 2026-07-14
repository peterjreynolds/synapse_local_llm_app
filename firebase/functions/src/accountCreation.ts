import {Timestamp} from "firebase-admin/firestore";
import {firebaseAdminAuth} from "./firebaseAdmin.js";
import {
  buildAccountClaims,
  buildSyntheticAccountEmail,
  type AccountRole,
  type AccountState,
} from "./identity.js";

export interface NewAccountIdentity {
  displayName: string;
  password: string;
  usernameNormalized: string;
}

export interface AccountProfileDocument {
  accountState: AccountState;
  allowed: boolean;
  avatarUrl: null;
  bio: string;
  createdAt: Timestamp;
  directoryVisible: boolean;
  displayName: string;
  lastSeenAt: null;
  mustChangePassword: boolean;
  online: boolean;
  role: AccountRole;
  updatedAt: Timestamp;
  username: string;
  usernameNormalized: string;
}

export async function createFirebaseAccountIdentity(
  identity: NewAccountIdentity,
  role: AccountRole,
  accountState: AccountState,
  mustChangePassword = false,
): Promise<string> {
  const account = await firebaseAdminAuth.createUser({
    disabled: false,
    displayName: identity.displayName,
    email: buildSyntheticAccountEmail(identity.usernameNormalized),
    emailVerified: true,
    password: identity.password,
  });
  try {
    await firebaseAdminAuth.setCustomUserClaims(
      account.uid,
      buildAccountClaims(role, accountState, mustChangePassword),
    );
    return account.uid;
  } catch (error) {
    await deleteOrDisableFirebaseAccount(account.uid);
    throw error;
  }
}

export function buildAccountProfileDocument(
  identity: Pick<NewAccountIdentity, "displayName" | "usernameNormalized">,
  role: AccountRole,
  accountState: AccountState,
  createdAt: Timestamp,
  mustChangePassword = false,
): AccountProfileDocument {
  const allowed = accountState === "ACTIVE";
  return {
    accountState,
    allowed,
    avatarUrl: null,
    bio: "",
    createdAt,
    directoryVisible: allowed,
    displayName: identity.displayName,
    lastSeenAt: null,
    mustChangePassword,
    online: false,
    role,
    updatedAt: createdAt,
    username: identity.usernameNormalized,
    usernameNormalized: identity.usernameNormalized,
  };
}

export async function deleteOrDisableFirebaseAccount(accountUid: string): Promise<string | null> {
  try {
    await firebaseAdminAuth.deleteUser(accountUid);
    return null;
  } catch {
    try {
      await firebaseAdminAuth.updateUser(accountUid, {disabled: true});
      return "AUTH_DELETE_ACCOUNT_DISABLED";
    } catch {
      return "AUTH_ACCOUNT_DISABLE_FAILED";
    }
  }
}
