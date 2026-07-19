#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const receiptPath = resolve(repositoryRoot, "release-gates.json");
const receipt = JSON.parse(readFileSync(receiptPath, "utf8"));
const allowedStatuses = new Set(["PASS", "FAIL", "BLOCKED"]);
const requiredIds = [
  "app-check-activation",
  "live-firebase-acceptance",
  "release-signer-comparison",
  "physical-device-galaxy-s9-api29",
  "physical-device-modern-api33-plus",
];
const requiredSupportingCheckIds = ["local-android-candidate"];
const requiredLocalAndroidChecks = [
  "testDebugUnitTest",
  "ktlintCheck",
  "lintDebug",
  "assembleDebug",
  "git diff --check",
];
const releaseMetadataPaths = new Set([
  "docs/release-gate-receipts.md",
  "docs/security-and-operations.md",
  "release-gates.json",
  "scripts/ci/verify-release-gates.mjs",
]);

function gitOutput(argumentsList) {
  return execFileSync("git", argumentsList, {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
}

function assertRecord(record, label) {
  if (record === null || typeof record !== "object" || Array.isArray(record)) {
    throw new Error(`${label} must be an object.`);
  }
}

function assertNonEmptyString(candidate, label) {
  if (typeof candidate !== "string" || candidate.trim().length === 0) {
    throw new Error(`${label} must be a non-empty string.`);
  }
}

function assertStringArray(candidates, label) {
  if (!Array.isArray(candidates) || candidates.some((candidate) => {
    return typeof candidate !== "string" || candidate.trim().length === 0;
  })) {
    throw new Error(`${label} must be an array of non-empty strings.`);
  }
}

function indexUniqueRecords(records, label) {
  if (!Array.isArray(records)) throw new Error(`${label} must be an array.`);
  const indexedRecords = new Map();
  for (const record of records) {
    assertRecord(record, `${label} entry`);
    assertNonEmptyString(record.id, `${label} entry id`);
    if (indexedRecords.has(record.id)) {
      throw new Error(`Duplicate ${label} entry: ${record.id}`);
    }
    indexedRecords.set(record.id, record);
  }
  return indexedRecords;
}

function assertStatus(record, label) {
  if (!allowedStatuses.has(record.status)) {
    throw new Error(`Invalid status for ${label}: ${record.status}`);
  }
}

assertRecord(receipt, "Release-gate receipt");

if (receipt.schemaVersion !== 1) {
  throw new Error(`Unsupported release-gate schema: ${receipt.schemaVersion}`);
}

if (typeof receipt.releaseReady !== "boolean") {
  throw new Error("releaseReady must be a boolean.");
}
if (!/^[0-9a-f]{40}$/.test(receipt.sourceCommit)) {
  throw new Error("sourceCommit must be a full lowercase Git commit SHA.");
}
if (typeof receipt.generatedAt !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(receipt.generatedAt)) {
  throw new Error("generatedAt must be an ISO-8601 UTC timestamp with second precision.");
}

const generatedAtMillis = Date.parse(receipt.generatedAt);
if (!Number.isFinite(generatedAtMillis)) {
  throw new Error(`generatedAt is invalid: ${receipt.generatedAt}`);
}

const gates = indexUniqueRecords(receipt.requiredGates, "required gate");
for (const id of requiredIds) {
  const gate = gates.get(id);
  if (!gate) throw new Error(`Missing required release gate: ${id}`);
}
for (const gate of gates.values()) {
  assertStatus(gate, gate.id);
  assertNonEmptyString(gate.summary, `${gate.id} summary`);
  assertStringArray(gate.evidence, `${gate.id} evidence`);
  assertStringArray(gate.nextActions, `${gate.id} nextActions`);
  if (gate.status === "PASS" && gate.evidence.length === 0) {
    throw new Error(`Passing gate ${gate.id} requires evidence.`);
  }
  if (gate.status === "PASS" && gate.nextActions.length !== 0) {
    throw new Error(`Passing gate ${gate.id} cannot retain next actions.`);
  }
  if (gate.status !== "PASS" && gate.nextActions.length === 0) {
    throw new Error(`Non-passing gate ${gate.id} requires a next action.`);
  }
}

const supportingChecks = indexUniqueRecords(receipt.supportingChecks, "supporting check");
for (const id of requiredSupportingCheckIds) {
  if (!supportingChecks.has(id)) throw new Error(`Missing required supporting check: ${id}`);
}
for (const supportingCheck of supportingChecks.values()) {
  assertStatus(supportingCheck, supportingCheck.id);
  if (supportingCheck.sourceCommit !== receipt.sourceCommit) {
    throw new Error(
      `${supportingCheck.id} sourceCommit does not match the release candidate.`,
    );
  }
  assertStringArray(supportingCheck.checks, `${supportingCheck.id} checks`);
  assertNonEmptyString(supportingCheck.note, `${supportingCheck.id} note`);
}
const localAndroidCheck = supportingChecks.get("local-android-candidate");
for (const requiredCheck of requiredLocalAndroidChecks) {
  if (!localAndroidCheck.checks.includes(requiredCheck)) {
    throw new Error(`local-android-candidate is missing check: ${requiredCheck}`);
  }
}

const head = gitOutput(["rev-parse", "HEAD"]);
let resolvedCandidateCommit;
try {
  resolvedCandidateCommit = gitOutput(["rev-parse", "--verify", `${receipt.sourceCommit}^{commit}`]);
} catch {
  throw new Error(`sourceCommit does not identify a commit in this repository: ${receipt.sourceCommit}`);
}
if (resolvedCandidateCommit !== receipt.sourceCommit) {
  throw new Error(`sourceCommit did not resolve exactly: ${receipt.sourceCommit}`);
}

try {
  execFileSync("git", ["merge-base", "--is-ancestor", receipt.sourceCommit, head], {
    cwd: repositoryRoot,
    stdio: "ignore",
  });
} catch {
  throw new Error(
    `Release candidate ${receipt.sourceCommit} is not an ancestor of HEAD ${head}.`,
  );
}

const committedPathsAfterCandidate = gitOutput([
  "diff",
  "--name-only",
  `${receipt.sourceCommit}..${head}`,
]).split("\n").filter(Boolean);
const staleCandidatePaths = committedPathsAfterCandidate.filter((path) => !releaseMetadataPaths.has(path));
if (staleCandidatePaths.length > 0) {
  throw new Error(
    `Release receipts are stale; committed candidate files changed after sourceCommit: ${staleCandidatePaths.join(", ")}`,
  );
}

const workingTreeStatus = gitOutput(["status", "--porcelain=v1", "--untracked-files=all"]);
if (workingTreeStatus.length > 0) {
  throw new Error("Release verification requires a clean working tree.");
}

const candidateCommittedAtMillis = Date.parse(gitOutput([
  "show",
  "-s",
  "--format=%cI",
  receipt.sourceCommit,
]));
if (generatedAtMillis < candidateCommittedAtMillis) {
  throw new Error("Release receipts were generated before the candidate commit.");
}
if (generatedAtMillis > Date.now() + 5 * 60 * 1000) {
  throw new Error("Release receipt generatedAt is unexpectedly in the future.");
}

const nonPassingGates = [...gates.values()].filter((gate) => gate.status !== "PASS");
const nonPassingSupportingChecks = [...supportingChecks.values()].filter((check) => check.status !== "PASS");

const computedReady = nonPassingGates.length === 0 && nonPassingSupportingChecks.length === 0;
if (receipt.releaseReady !== computedReady) {
  throw new Error(
    `releaseReady=${receipt.releaseReady} does not match gate statuses; expected ${computedReady}.`,
  );
}

if (!computedReady) {
  console.error("Synapse Chat release is BLOCKED.");
  for (const gate of nonPassingGates) {
    console.error(`- ${gate.id}: ${gate.status} — ${gate.summary}`);
  }
  for (const supportingCheck of nonPassingSupportingChecks) {
    console.error(`- ${supportingCheck.id}: ${supportingCheck.status} — supporting check did not pass`);
  }
  process.exitCode = 1;
} else {
  console.log(`Synapse Chat release gates PASS for candidate ${receipt.sourceCommit}.`);
}
