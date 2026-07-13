import {applicationDefault, getApps, initializeApp} from "firebase-admin/app";
import {getAuth} from "firebase-admin/auth";
import {Timestamp, getFirestore} from "firebase-admin/firestore";
import {buildSyntheticAccountEmail, normalizeUsername, type AllowedUsername} from "./domain.js";

const PROJECT_ID = process.env.GOOGLE_CLOUD_PROJECT ?? "synapse-chat-pjr-2026";
const BOOTSTRAP_USERNAMES: readonly AllowedUsername[] = ["Peter", "Trish"];
const MINIMUM_PASSWORD_LENGTH = 8;

interface BootstrapCredential {
  password: string;
  username: AllowedUsername;
}

interface CreatedBootstrapAccount {
  email: string;
  uid: string;
  username: AllowedUsername;
}

async function readHiddenSecret(prompt: string): Promise<string> {
  if (!process.stdin.isTTY || !process.stdout.isTTY || !process.stdin.setRawMode) {
    throw new Error("Hidden password input requires an interactive terminal.");
  }

  process.stdout.write(prompt);
  process.stdin.setEncoding("utf8");
  process.stdin.setRawMode(true);
  process.stdin.resume();

  return new Promise((resolve, reject) => {
    let secret = "";
    const restoreTerminal = (): void => {
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stdin.removeListener("data", onInput);
      process.stdout.write("\n");
    };
    const onInput = (input: string): void => {
      for (const character of input) {
        if (character === "\u0003") {
          restoreTerminal();
          reject(new Error("Provisioning cancelled."));
          return;
        }
        if (character === "\r" || character === "\n") {
          restoreTerminal();
          resolve(secret);
          return;
        }
        if (character === "\u007f" || character === "\b") {
          secret = secret.slice(0, -1);
          continue;
        }
        if (character >= " " && character !== "\u007f") {
          secret += character;
        }
      }
    };
    process.stdin.on("data", onInput);
  });
}

async function resolveBootstrapCredentials(): Promise<BootstrapCredential[]> {
  const credentials: BootstrapCredential[] = [];
  for (const username of BOOTSTRAP_USERNAMES) {
    const environmentName = `SYNAPSE_${username.toUpperCase()}_PASSWORD`;
    const password = process.env[environmentName] ?? (await readHiddenSecret(`${username} password: `));
    if (password.length < MINIMUM_PASSWORD_LENGTH) {
      throw new Error(`${username}'s password must contain at least ${MINIMUM_PASSWORD_LENGTH} characters.`);
    }
    credentials.push({password, username});
  }
  return credentials;
}

async function assertAccountsAreUnprovisioned(credentials: readonly BootstrapCredential[]): Promise<void> {
  const auth = getAuth();
  const firestore = getFirestore();
  for (const credential of credentials) {
    const normalizedUsername = normalizeUsername(credential.username);
    const usernameSnapshot = await firestore.doc(`usernames/${normalizedUsername}`).get();
    if (usernameSnapshot.exists) {
      throw new Error(`${credential.username} is already provisioned.`);
    }
    try {
      await auth.getUserByEmail(buildSyntheticAccountEmail(credential.username));
      throw new Error(`${credential.username} is already provisioned.`);
    } catch (error) {
      if ((error as {code?: string}).code !== "auth/user-not-found") {
        throw error;
      }
    }
  }
}

async function createBootstrapAccounts(
  credentials: readonly BootstrapCredential[],
): Promise<CreatedBootstrapAccount[]> {
  const auth = getAuth();
  const firestore = getFirestore();
  const createdAccounts: CreatedBootstrapAccount[] = [];
  try {
    for (const credential of credentials) {
      const email = buildSyntheticAccountEmail(credential.username);
      const account = await auth.createUser({
        disabled: false,
        displayName: credential.username,
        email,
        emailVerified: true,
        password: credential.password,
      });
      createdAccounts.push({email, uid: account.uid, username: credential.username});
    }

    const createdAt = Timestamp.now();
    const writes = firestore.batch();
    for (const account of createdAccounts) {
      const normalizedUsername = normalizeUsername(account.username);
      writes.create(firestore.doc(`usernames/${normalizedUsername}`), {
        createdAt,
        uid: account.uid,
        username: account.username,
        usernameNormalized: normalizedUsername,
      });
      writes.create(firestore.doc(`profiles/${account.uid}`), {
        allowed: true,
        avatarUrl: null,
        bio: "",
        createdAt,
        directoryVisible: true,
        displayName: account.username,
        lastSeenAt: null,
        online: false,
        updatedAt: createdAt,
        username: account.username,
        usernameNormalized: normalizedUsername,
      });
      writes.create(firestore.doc(`accountProvisioningReceipts/${account.uid}`), {
        actor: "bootstrap-command",
        createdAt,
        uid: account.uid,
        username: account.username,
      });
    }
    await writes.commit();
    return createdAccounts;
  } catch (error) {
    await Promise.allSettled(createdAccounts.map((account) => auth.deleteUser(account.uid)));
    throw error;
  }
}

async function main(): Promise<void> {
  if (getApps().length === 0) {
    initializeApp({credential: applicationDefault(), projectId: PROJECT_ID});
  }
  const credentials = await resolveBootstrapCredentials();
  try {
    await assertAccountsAreUnprovisioned(credentials);
    const createdAccounts = await createBootstrapAccounts(credentials);
    for (const account of createdAccounts) {
      process.stdout.write(`Provisioned ${account.username} (${account.uid}).\n`);
    }
  } finally {
    credentials.splice(0, credentials.length);
    delete process.env.SYNAPSE_PETER_PASSWORD;
    delete process.env.SYNAPSE_TRISH_PASSWORD;
  }
}

void main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : "Unknown provisioning failure.";
  process.stderr.write(`Provisioning failed: ${message}\n`);
  process.exitCode = 1;
});
