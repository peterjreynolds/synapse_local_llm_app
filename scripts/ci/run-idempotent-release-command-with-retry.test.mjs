import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

const retryScriptPath = fileURLToPath(
  new URL("./run-idempotent-release-command-with-retry.sh", import.meta.url),
);

function createFlakyCommand(testContext) {
  const testDirectory = mkdtempSync(join(tmpdir(), "synapse-release-retry-"));
  const commandPath = join(testDirectory, "flaky-command.sh");
  const attemptReceiptPath = join(testDirectory, "attempt-count.txt");

  testContext.after(() => rmSync(testDirectory, {recursive: true, force: true}));
  writeFileSync(
    commandPath,
    `#!/usr/bin/env bash
set -euo pipefail
attempt_receipt_path="$1"
success_attempt="$2"
attempt_count=0
if [ -f "$attempt_receipt_path" ]; then
  attempt_count="$(cat "$attempt_receipt_path")"
fi
attempt_count=$((attempt_count + 1))
printf '%s\n' "$attempt_count" > "$attempt_receipt_path"
if [ "$attempt_count" -lt "$success_attempt" ]; then
  exit 75
fi
printf 'published\n'
`,
  );
  chmodSync(commandPath, 0o700);

  return {attemptReceiptPath, commandPath};
}

function runRetryCommand(commandArguments) {
  return spawnSync("bash", [retryScriptPath, ...commandArguments], {
    encoding: "utf8",
    env: {
      ...process.env,
      SYNAPSE_RELEASE_MAX_ATTEMPTS: "3",
      SYNAPSE_RELEASE_OPERATION: "test publication",
      SYNAPSE_RELEASE_RETRY_BASE_DELAY_SECONDS: "0",
    },
  });
}

test("retries an idempotent release command until it succeeds", (testContext) => {
  const {attemptReceiptPath, commandPath} = createFlakyCommand(testContext);

  const execution = runRetryCommand([commandPath, attemptReceiptPath, "3"]);

  assert.equal(execution.status, 0, execution.stderr);
  assert.equal(execution.stdout, "published\n");
  assert.equal(readFileSync(attemptReceiptPath, "utf8"), "3\n");
  assert.match(execution.stderr, /attempt 3\/3/);
});

test("fails closed after the bounded attempt count", (testContext) => {
  const {attemptReceiptPath, commandPath} = createFlakyCommand(testContext);

  const execution = runRetryCommand([commandPath, attemptReceiptPath, "4"]);

  assert.equal(execution.status, 75);
  assert.equal(readFileSync(attemptReceiptPath, "utf8"), "3\n");
  assert.match(execution.stderr, /failed after 3 attempts/);
});

test("rejects an empty command", () => {
  const execution = spawnSync("bash", [retryScriptPath], {encoding: "utf8"});

  assert.equal(execution.status, 64);
  assert.match(execution.stderr, /usage:/);
});
