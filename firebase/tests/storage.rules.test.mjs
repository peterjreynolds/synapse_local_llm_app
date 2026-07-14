import fs from "node:fs";
import {after, before, beforeEach, test} from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {Timestamp, doc, setDoc} from "firebase/firestore";
import {getMetadata, ref, uploadBytes} from "firebase/storage";

const PROJECT_ID = "demo-synapse-chat";
const BUCKET = `gs://${PROJECT_ID}.appspot.com`;
const PETER_UID = "peter-uid";
const TRISH_UID = "trish-uid";
const MALLORY_UID = "mallory-uid";
const storageRules = fs.readFileSync(new URL("../storage.rules", import.meta.url), "utf8");

let testEnvironment;

async function seedProfiles() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    for (const [uid, allowed] of [
      [PETER_UID, true],
      [TRISH_UID, true],
      [MALLORY_UID, false],
    ]) {
      await setDoc(doc(firestore, "profiles", uid), {
        accountState: allowed ? "ACTIVE" : "DISABLED",
        allowed,
        avatarUrl: null,
        bio: "",
        createdAt: Timestamp.fromMillis(1),
        directoryVisible: true,
        displayName: uid,
        lastSeenAt: null,
        mustChangePassword: false,
        online: false,
        role: "USER",
        updatedAt: Timestamp.fromMillis(1),
        username: uid,
        usernameNormalized: uid,
      });
    }
  });
}

function activeContext(uid) {
  return testEnvironment.authenticatedContext(uid, {
    accountState: "ACTIVE",
    role: "USER",
  });
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    storage: {rules: storageRules},
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
  await testEnvironment.clearStorage();
  await seedProfiles();
});

after(async () => {
  await testEnvironment.cleanup();
});

test("allows a user to upload only their own bounded image avatar", async () => {
  const peterStorage = activeContext(PETER_UID).storage(BUCKET);
  await assertSucceeds(
    uploadBytes(ref(peterStorage, `avatars/${PETER_UID}/avatar.jpg`), new Uint8Array([1, 2, 3]), {
      contentType: "image/jpeg",
    }),
  );
  await assertFails(
    uploadBytes(ref(peterStorage, `avatars/${TRISH_UID}/avatar.jpg`), new Uint8Array([1, 2, 3]), {
      contentType: "image/jpeg",
    }),
  );
  await assertFails(
    uploadBytes(ref(peterStorage, `avatars/${PETER_UID}/avatar.jpg`), new Uint8Array([1, 2, 3]), {
      contentType: "text/plain",
    }),
  );
});

test("allows allowlisted profile-avatar reads but denies non-allowlisted access", async () => {
  const peterStorage = activeContext(PETER_UID).storage(BUCKET);
  const avatar = ref(peterStorage, `avatars/${PETER_UID}/avatar.png`);
  await assertSucceeds(
    uploadBytes(avatar, new Uint8Array([1, 2, 3]), {contentType: "image/png"}),
  );

  const trishStorage = activeContext(TRISH_UID).storage(BUCKET);
  await assertSucceeds(getMetadata(ref(trishStorage, `avatars/${PETER_UID}/avatar.png`)));

  const malloryStorage = activeContext(MALLORY_UID).storage(BUCKET);
  await assertFails(getMetadata(ref(malloryStorage, `avatars/${PETER_UID}/avatar.png`)));
});
