import {pathToFileURL} from "node:url";

const ANDROID_VERSION_CODE_LIMIT = 2_100_000_000;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const SOURCE_COMMIT_PATTERN = /^[a-f0-9]{40}$/;
const APPLICATION_ID_PATTERN = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/;
const REPOSITORY_PATTERN = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
const RELEASE_TOKEN_PATTERN = /^[A-Za-z0-9._-]+$/;
const ABI_PATTERN = /^[a-z0-9_-]+$/;

export function buildSynapsePrivateUpdateMetadata(candidate) {
  assertRecord(candidate, "Update metadata candidate");
  const applicationId = parseMatchingString(
    candidate.applicationId,
    APPLICATION_ID_PATTERN,
    "application id",
  );
  const versionCode = parsePositiveInteger(candidate.versionCode, "version code");
  if (versionCode > ANDROID_VERSION_CODE_LIMIT) {
    throw new RangeError(`Version code ${versionCode} exceeds the Android limit.`);
  }
  const versionName = parseBoundedString(candidate.versionName, "version name", 1, 64);
  const minimumAndroidApi = parsePositiveInteger(
    candidate.minimumAndroidApi,
    "minimum Android API",
  );
  const apkBytes = parsePositiveInteger(candidate.apkBytes, "APK byte count");
  const apkName = parseMatchingString(candidate.apkName, RELEASE_TOKEN_PATTERN, "APK name");
  if (!apkName.endsWith(".apk")) {
    throw new TypeError("APK name must end with .apk.");
  }
  const apkSha256 = parseMatchingString(candidate.apkSha256, SHA256_PATTERN, "APK SHA-256");
  const signerSha256 = parseMatchingString(
    candidate.signerSha256,
    SHA256_PATTERN,
    "signer SHA-256",
  );
  const sourceCommit = parseMatchingString(
    candidate.sourceCommit,
    SOURCE_COMMIT_PATTERN,
    "source commit",
  );
  const releaseTag = parseMatchingString(
    candidate.releaseTag,
    RELEASE_TOKEN_PATTERN,
    "release tag",
  );
  const repository = parseMatchingString(
    candidate.repository,
    REPOSITORY_PATTERN,
    "GitHub repository",
  );
  const supportedAbis = parseSupportedAbis(candidate.supportedAbis);
  const publishedAt = parsePublishedAt(candidate.publishedAt);

  return {
    schemaVersion: 1,
    applicationId,
    versionCode,
    versionName,
    minimumAndroidApi,
    supportedAbis,
    apk: {
      name: apkName,
      byteCount: apkBytes,
      sha256: apkSha256,
      signerSha256,
      downloadUrl:
        `https://github.com/${repository}/releases/download/` +
        `${encodeURIComponent(releaseTag)}/${encodeURIComponent(apkName)}`,
    },
    source: {
      repository,
      commit: sourceCommit,
      releaseTag,
    },
    publishedAt,
  };
}

function assertRecord(candidate, label) {
  if (candidate === null || typeof candidate !== "object" || Array.isArray(candidate)) {
    throw new TypeError(`${label} must be an object.`);
  }
}

function parseMatchingString(candidate, pattern, label) {
  const value = parseBoundedString(candidate, label, 1, 256);
  if (!pattern.test(value)) {
    throw new TypeError(`${label} has an unsupported format.`);
  }
  return value;
}

function parseBoundedString(candidate, label, minimumLength, maximumLength) {
  if (
    typeof candidate !== "string" ||
    candidate.length < minimumLength ||
    candidate.length > maximumLength ||
    candidate.trim() !== candidate
  ) {
    throw new TypeError(`${label} must contain ${minimumLength}-${maximumLength} characters.`);
  }
  return candidate;
}

function parsePositiveInteger(candidate, label) {
  const value = typeof candidate === "number" ? String(candidate) : candidate;
  if (typeof value !== "string" || !/^[1-9]\d*$/.test(value)) {
    throw new TypeError(`${label} must be a positive integer.`);
  }
  const parsedValue = Number(value);
  if (!Number.isSafeInteger(parsedValue)) {
    throw new RangeError(`${label} exceeds JavaScript's safe integer range.`);
  }
  return parsedValue;
}

function parseSupportedAbis(candidate) {
  if (!Array.isArray(candidate) || candidate.length === 0) {
    throw new TypeError("supported ABIs must be a non-empty array.");
  }
  const supportedAbis = candidate.map((abi) =>
    parseMatchingString(abi, ABI_PATTERN, "supported ABI"),
  );
  if (new Set(supportedAbis).size !== supportedAbis.length) {
    throw new TypeError("supported ABIs must not contain duplicates.");
  }
  return supportedAbis;
}

function parsePublishedAt(candidate) {
  const publishedAt = parseBoundedString(candidate, "published at", 20, 20);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(publishedAt)) {
    throw new TypeError("published at must use UTC ISO-8601 second precision.");
  }
  if (!Number.isFinite(Date.parse(publishedAt))) {
    throw new TypeError("published at must be a real timestamp.");
  }
  return publishedAt;
}

function requiredEnvironmentValue(name) {
  const value = process.env[name];
  if (value === undefined) {
    throw new Error(`${name} is required.`);
  }
  return value;
}

function runCommandLine() {
  const metadata = buildSynapsePrivateUpdateMetadata({
    applicationId: requiredEnvironmentValue("SYNAPSE_PRIVATE_APPLICATION_ID"),
    versionCode: requiredEnvironmentValue("SYNAPSE_VERSION_CODE"),
    versionName: requiredEnvironmentValue("SYNAPSE_VERSION_NAME"),
    minimumAndroidApi: requiredEnvironmentValue("SYNAPSE_PRIVATE_MINIMUM_ANDROID_API"),
    supportedAbis: requiredEnvironmentValue("SYNAPSE_PRIVATE_APK_ABIS").split(","),
    apkName: requiredEnvironmentValue("SYNAPSE_PRIVATE_APK_NAME"),
    apkBytes: requiredEnvironmentValue("SYNAPSE_PRIVATE_APK_BYTES"),
    apkSha256: requiredEnvironmentValue("SYNAPSE_PRIVATE_APK_SHA256"),
    signerSha256: requiredEnvironmentValue("SYNAPSE_PRIVATE_SIGNER_SHA256"),
    sourceCommit: requiredEnvironmentValue("SYNAPSE_PRIVATE_SOURCE_COMMIT"),
    releaseTag: requiredEnvironmentValue("SYNAPSE_PRIVATE_RELEASE_TAG"),
    repository: requiredEnvironmentValue("GITHUB_REPOSITORY"),
    publishedAt: requiredEnvironmentValue("SYNAPSE_PRIVATE_PUBLISHED_AT"),
  });
  process.stdout.write(`${JSON.stringify(metadata, null, 2)}\n`);
}

const invokedPath = process.argv[1];
if (invokedPath !== undefined && import.meta.url === pathToFileURL(invokedPath).href) {
  try {
    runCommandLine();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown update metadata failure.";
    process.stderr.write(`Synapse Private update metadata failed: ${message}\n`);
    process.exitCode = 1;
  }
}
