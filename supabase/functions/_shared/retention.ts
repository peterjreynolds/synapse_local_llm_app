import { expectStringField } from "./backend.ts";

export interface LeasedAttachmentBatch {
  readonly leaseId: string;
  readonly messageCount: number;
  readonly objectPaths: readonly string[];
}

const UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const OBJECT_PATH_PATTERN = new RegExp(
  `^${UUID_PATTERN}/${UUID_PATTERN}/${UUID_PATTERN}(?:[.]thumbnail)?[.]ciphertext$`,
  "u",
);

export function parseLeasedAttachmentBatch(
  rows: ReadonlyArray<Record<string, unknown>>,
): LeasedAttachmentBatch | null {
  if (rows.length === 0) return null;
  if (rows.length > 100) throw new Error("The attachment purge lease is too large");

  const leaseId = expectStringField(rows[0]!, "lease_id");
  const objectPaths: string[] = [];
  for (const row of rows) {
    if (expectStringField(row, "lease_id") !== leaseId || !Array.isArray(row.object_paths)) {
      throw new Error("The attachment purge lease is inconsistent");
    }
    for (const path of row.object_paths) {
      if (typeof path !== "string" || !OBJECT_PATH_PATTERN.test(path)) {
        throw new Error("The backend returned a non-canonical Storage path");
      }
      objectPaths.push(path);
    }
  }

  if (objectPaths.length < 1 || objectPaths.length > 1600) {
    throw new Error("The attachment purge object count is invalid");
  }
  if (new Set(objectPaths).size !== objectPaths.length) {
    throw new Error("The attachment purge lease contains duplicate Storage paths");
  }
  return { leaseId, messageCount: rows.length, objectPaths };
}

export async function removeLeasedAttachmentObjects(
  batch: LeasedAttachmentBatch,
  removePaths: (paths: readonly string[]) => Promise<void>,
): Promise<void> {
  for (let offset = 0; offset < batch.objectPaths.length; offset += 100) {
    await removePaths(batch.objectPaths.slice(offset, offset + 100));
  }
}
