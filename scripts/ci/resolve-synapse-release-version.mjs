import {pathToFileURL} from "node:url";

const ANDROID_VERSION_CODE_LIMIT = 2_100_000_000;
const RELEASE_BODY_BYTE_LIMIT = 64 * 1024;
const VERSION_CODE_OFFSET = 2_000;
const VERSION_CODE_PATTERN = /\bVersion code:\s*(\d+)\b/i;

export function resolveSynapseReleaseVersion({
  workflowRunNumber,
  currentReleaseBody,
  minimumVersionCode = 1,
}) {
  const normalizedRunNumber = parsePositiveInteger(workflowRunNumber, "workflow run number");
  const normalizedMinimumVersionCode = parsePositiveInteger(
    minimumVersionCode,
    "minimum version code",
  );
  if (currentReleaseBody !== null && typeof currentReleaseBody !== "string") {
    throw new TypeError("Current release body must be a string or null.");
  }

  const workflowVersionCode = VERSION_CODE_OFFSET + normalizedRunNumber;
  const currentVersionCode = currentReleaseBody === null
    ? null
    : parseCurrentReleaseVersionCode(currentReleaseBody);
  const versionCode = Math.max(
    workflowVersionCode,
    currentVersionCode === null ? workflowVersionCode : currentVersionCode + 1,
    normalizedMinimumVersionCode,
  );
  if (versionCode > ANDROID_VERSION_CODE_LIMIT) {
    throw new RangeError(`Resolved Android version code ${versionCode} exceeds the supported limit.`);
  }

  return {
    versionCode,
    versionName: `0.1.${versionCode}`,
  };
}

function parseCurrentReleaseVersionCode(releaseBody) {
  const match = VERSION_CODE_PATTERN.exec(releaseBody);
  if (match === null) {
    throw new Error("Existing Synapse release does not contain a valid Version code receipt.");
  }
  return parsePositiveInteger(match[1], "current release version code");
}

function parsePositiveInteger(rawValue, label) {
  const normalizedValue = typeof rawValue === "number" ? String(rawValue) : rawValue;
  if (typeof normalizedValue !== "string" || !/^[1-9]\d*$/.test(normalizedValue)) {
    throw new TypeError(`${label} must be a positive integer.`);
  }
  const parsedValue = Number(normalizedValue);
  if (!Number.isSafeInteger(parsedValue)) {
    throw new RangeError(`${label} exceeds JavaScript's safe integer range.`);
  }
  return parsedValue;
}

async function readBoundedReleaseBody() {
  const chunks = [];
  let byteCount = 0;
  for await (const chunk of process.stdin) {
    byteCount += chunk.length;
    if (byteCount > RELEASE_BODY_BYTE_LIMIT) {
      throw new RangeError("Current release body exceeds the 64 KiB policy limit.");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function runCommandLine() {
  const releaseExists = process.env.SYNAPSE_RELEASE_EXISTS;
  if (releaseExists !== "0" && releaseExists !== "1") {
    throw new Error("SYNAPSE_RELEASE_EXISTS must be 0 or 1.");
  }
  const currentReleaseBody = releaseExists === "1" ? await readBoundedReleaseBody() : null;
  const versionPlan = resolveSynapseReleaseVersion({
    workflowRunNumber: process.env.GITHUB_RUN_NUMBER,
    currentReleaseBody,
    minimumVersionCode: process.env.SYNAPSE_MINIMUM_VERSION_CODE ?? 1,
  });
  process.stdout.write(`${JSON.stringify(versionPlan)}\n`);
}

const invokedPath = process.argv[1];
if (invokedPath !== undefined && import.meta.url === pathToFileURL(invokedPath).href) {
  void runCommandLine().catch((error) => {
    const message = error instanceof Error ? error.message : "Unknown release version resolution failure.";
    process.stderr.write(`Release version resolution failed: ${message}\n`);
    process.exitCode = 1;
  });
}
