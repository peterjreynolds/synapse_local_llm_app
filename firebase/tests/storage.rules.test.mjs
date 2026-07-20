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
const GROUP_ROOM_ID = `group_${"a".repeat(32)}`;
const ATTACHMENT_ID = "attachment-12345678-1234-4123-8123-123456789abc";
const MESSAGE_ID = "message-123";
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
    await setDoc(doc(firestore, "rooms", GROUP_ROOM_ID), {
      activeMemberIds: [PETER_UID, TRISH_UID],
      kind: "GROUP",
    });
    await setDoc(doc(firestore, "rooms", GROUP_ROOM_ID, "members", PETER_UID), {
      active: true,
      role: "OWNER",
      uid: PETER_UID,
    });
    await setDoc(doc(firestore, "rooms", GROUP_ROOM_ID, "members", TRISH_UID), {
      active: true,
      role: "MEMBER",
      uid: TRISH_UID,
    });
  });
}

async function seedAttachmentUpload({
  actorUid = PETER_UID,
  byteCount = 3,
  kind = "DOCUMENT",
  mimeType = "application/pdf",
  status = "PENDING",
} = {}) {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "attachmentUploads", ATTACHMENT_ID), {
      actorUid,
      attachmentId: ATTACHMENT_ID,
      byteCount,
      contentObjectPath: `roomAttachments/${GROUP_ROOM_ID}/${MESSAGE_ID}/${ATTACHMENT_ID}/content`,
      kind,
      messageId: MESSAGE_ID,
      mimeType,
      roomId: GROUP_ROOM_ID,
      status,
      thumbnailObjectPath: kind === "IMAGE" || kind === "VIDEO" ?
        `roomAttachments/${GROUP_ROOM_ID}/${MESSAGE_ID}/${ATTACHMENT_ID}/thumbnail` : null,
    });
  });
}

function attachmentMetadata(variant) {
  return {
    attachmentId: ATTACHMENT_ID,
    messageId: MESSAGE_ID,
    ownerUid: PETER_UID,
    roomId: GROUP_ROOM_ID,
    variant,
  };
}

function activeContext(uid) {
  return testEnvironment.authenticatedContext(uid, {
    accountState: "ACTIVE",
    mustChangePassword: false,
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

test("denies avatar access while a password change is required", async () => {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    await setDoc(
      doc(firestore, "profiles", PETER_UID),
      {mustChangePassword: true},
      {merge: true},
    );
  });
  const peterStorage = testEnvironment.authenticatedContext(PETER_UID, {
    accountState: "ACTIVE",
    mustChangePassword: true,
    role: "USER",
  }).storage(BUCKET);
  await assertFails(
    uploadBytes(
      ref(peterStorage, `avatars/${PETER_UID}/avatar.jpg`),
      new Uint8Array([1, 2, 3]),
      {contentType: "image/jpeg"},
    ),
  );
});

test("allows bounded group avatars only for active group administrators", async () => {
  const avatarPath = `groupAvatars/${GROUP_ROOM_ID}/avatar_${"b".repeat(32)}.webp`;
  const peterStorage = activeContext(PETER_UID).storage(BUCKET);
  await assertSucceeds(
    uploadBytes(ref(peterStorage, avatarPath), new Uint8Array([1, 2, 3]), {
      contentType: "image/webp",
    }),
  );

  const trishStorage = activeContext(TRISH_UID).storage(BUCKET);
  await assertSucceeds(getMetadata(ref(trishStorage, avatarPath)));
  await assertFails(
    uploadBytes(ref(trishStorage, avatarPath), new Uint8Array([4, 5, 6]), {
      contentType: "image/webp",
    }),
  );
  await assertFails(
    uploadBytes(
      ref(peterStorage, `groupAvatars/${GROUP_ROOM_ID}/avatar_${"c".repeat(32)}.apk`),
      new Uint8Array([1, 2, 3]),
      {contentType: "application/vnd.android.package-archive"},
    ),
  );

  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "rooms", GROUP_ROOM_ID, "members", TRISH_UID),
      {active: false},
      {merge: true},
    );
  });
  await assertFails(getMetadata(ref(trishStorage, avatarPath)));
});

test("allows only the upload owner to write exact intent-bound attachment content", async () => {
  await seedAttachmentUpload();
  const attachmentPath = `roomAttachments/${GROUP_ROOM_ID}/${MESSAGE_ID}/${ATTACHMENT_ID}/content`;
  const peterStorage = activeContext(PETER_UID).storage(BUCKET);
  await assertSucceeds(uploadBytes(
    ref(peterStorage, attachmentPath),
    new Uint8Array([1, 2, 3]),
    {contentType: "application/pdf", customMetadata: attachmentMetadata("content")},
  ));
  const trishStorage = activeContext(TRISH_UID).storage(BUCKET);
  await assertFails(uploadBytes(
    ref(trishStorage, attachmentPath),
    new Uint8Array([1, 2, 3]),
    {contentType: "application/pdf", customMetadata: attachmentMetadata("content")},
  ));
  await assertFails(uploadBytes(
    ref(peterStorage, attachmentPath),
    new Uint8Array([1, 2]),
    {contentType: "application/pdf", customMetadata: attachmentMetadata("content")},
  ));
  await assertFails(uploadBytes(
    ref(peterStorage, attachmentPath),
    new Uint8Array([1, 2, 3]),
    {contentType: "application/vnd.android.package-archive", customMetadata: attachmentMetadata("content")},
  ));
});

test("requires bounded JPEG thumbnails and revokes reads after membership deletion", async () => {
  await seedAttachmentUpload({kind: "IMAGE", mimeType: "image/png"});
  const prefix = `roomAttachments/${GROUP_ROOM_ID}/${MESSAGE_ID}/${ATTACHMENT_ID}`;
  const peterStorage = activeContext(PETER_UID).storage(BUCKET);
  await assertSucceeds(uploadBytes(
    ref(peterStorage, `${prefix}/content`),
    new Uint8Array([1, 2, 3]),
    {contentType: "image/png", customMetadata: attachmentMetadata("content")},
  ));
  await assertSucceeds(uploadBytes(
    ref(peterStorage, `${prefix}/thumbnail`),
    new Uint8Array([4, 5, 6]),
    {contentType: "image/jpeg", customMetadata: attachmentMetadata("thumbnail")},
  ));
  await assertFails(uploadBytes(
    ref(peterStorage, `${prefix}/thumbnail`),
    new Uint8Array([4, 5, 6]),
    {contentType: "image/png", customMetadata: attachmentMetadata("thumbnail")},
  ));

  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "attachmentUploads", ATTACHMENT_ID), {status: "ATTACHED"}, {merge: true});
  });
  const trishStorage = activeContext(TRISH_UID).storage(BUCKET);
  await assertSucceeds(getMetadata(ref(trishStorage, `${prefix}/content`)));
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "rooms", GROUP_ROOM_ID, "members", TRISH_UID),
      {active: false},
      {merge: true},
    );
  });
  await assertFails(getMetadata(ref(trishStorage, `${prefix}/content`)));
});

test("allows a bounded JPEG poster only for a declared video upload", async () => {
  await seedAttachmentUpload({kind: "VIDEO", mimeType: "video/mp4"});
  const prefix = `roomAttachments/${GROUP_ROOM_ID}/${MESSAGE_ID}/${ATTACHMENT_ID}`;
  const peterStorage = activeContext(PETER_UID).storage(BUCKET);

  await assertSucceeds(uploadBytes(
    ref(peterStorage, `${prefix}/content`),
    new Uint8Array([1, 2, 3]),
    {contentType: "video/mp4", customMetadata: attachmentMetadata("content")},
  ));
  await assertSucceeds(uploadBytes(
    ref(peterStorage, `${prefix}/thumbnail`),
    new Uint8Array([4, 5, 6]),
    {contentType: "image/jpeg", customMetadata: attachmentMetadata("thumbnail")},
  ));

  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "attachmentUploads", ATTACHMENT_ID),
      {kind: "DOCUMENT", thumbnailObjectPath: null},
      {merge: true},
    );
  });
  await assertFails(uploadBytes(
    ref(peterStorage, `${prefix}/thumbnail`),
    new Uint8Array([7, 8, 9]),
    {contentType: "image/jpeg", customMetadata: attachmentMetadata("thumbnail")},
  ));
});
