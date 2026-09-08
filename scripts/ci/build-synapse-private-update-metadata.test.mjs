import assert from "node:assert/strict";
import test from "node:test";
import {buildSynapsePrivateUpdateMetadata} from "./build-synapse-private-update-metadata.mjs";

const validCandidate = {
  applicationId: "app.synapse.privatechat",
  versionCode: "2031",
  versionName: "0.1.2031",
  minimumAndroidApi: "25",
  supportedAbis: ["arm64-v8a", "armeabi-v7a", "x86_64"],
  apkName: "Synapse-Private.apk",
  apkBytes: "48151623",
  apkSha256: "a".repeat(64),
  signerSha256: "b".repeat(64),
  sourceCommit: "c".repeat(40),
  releaseTag: "synapse-private",
  repository: "synapse-owner/synapse-repository",
  publishedAt: "2026-08-20T18:05:00Z",
};

test("builds complete machine-readable update metadata", () => {
  assert.deepEqual(buildSynapsePrivateUpdateMetadata(validCandidate), {
    schemaVersion: 1,
    applicationId: "app.synapse.privatechat",
    versionCode: 2031,
    versionName: "0.1.2031",
    minimumAndroidApi: 25,
    supportedAbis: ["arm64-v8a", "armeabi-v7a", "x86_64"],
    apk: {
      name: "Synapse-Private.apk",
      byteCount: 48151623,
      sha256: "a".repeat(64),
      signerSha256: "b".repeat(64),
      downloadUrl:
        "https://github.com/synapse-owner/synapse-repository/releases/download/" +
        "synapse-private/Synapse-Private.apk",
    },
    source: {
      repository: "synapse-owner/synapse-repository",
      commit: "c".repeat(40),
      releaseTag: "synapse-private",
    },
    publishedAt: "2026-08-20T18:05:00Z",
  });
});

test("rejects an Android version code above the platform limit", () => {
  assert.throws(
    () => buildSynapsePrivateUpdateMetadata({...validCandidate, versionCode: "2100000001"}),
    /exceeds the Android limit/,
  );
});

test("rejects malformed or uppercase checksums", () => {
  assert.throws(
    () => buildSynapsePrivateUpdateMetadata({...validCandidate, apkSha256: "A".repeat(64)}),
    /APK SHA-256 has an unsupported format/,
  );
});

test("rejects duplicate APK ABIs", () => {
  assert.throws(
    () =>
      buildSynapsePrivateUpdateMetadata({
        ...validCandidate,
        supportedAbis: ["arm64-v8a", "arm64-v8a"],
      }),
    /must not contain duplicates/,
  );
});

test("rejects an APK name that is not an APK", () => {
  assert.throws(
    () => buildSynapsePrivateUpdateMetadata({...validCandidate, apkName: "Synapse-Private.zip"}),
    /must end with \.apk/,
  );
});

test("rejects timestamps without UTC second precision", () => {
  assert.throws(
    () => buildSynapsePrivateUpdateMetadata({...validCandidate, publishedAt: "2026-08-20"}),
    /published at must contain/,
  );
});
