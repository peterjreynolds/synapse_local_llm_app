const encoder = new TextEncoder();

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function postgresByteaFromHex(hex: string): string {
  return `\\x${hex}`;
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return bytesToHex(new Uint8Array(digest));
}

export async function hmacSha256Hex(secret: string, value: string): Promise<string> {
  if (encoder.encode(secret).byteLength < 32) {
    throw new Error("HMAC secrets must contain at least 32 UTF-8 bytes");
  }
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return bytesToHex(new Uint8Array(digest));
}

function base64UrlFromHex(hex: string): string {
  if (!/^[0-9a-f]{64}$/u.test(hex)) throw new Error("Expected a 32-byte hex value");
  let binary = "";
  for (let index = 0; index < hex.length; index += 2) {
    binary += String.fromCharCode(Number.parseInt(hex.slice(index, index + 2), 16));
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export async function deriveInviteCode(
  inviteDerivationKey: string,
  actorUserId: string,
  inviteKind: "ACCOUNT_REGISTRATION" | "ROOM_MEMBERSHIP",
  roomId: string | null,
  clientMutationId: string,
): Promise<string> {
  const domain = [
    "synapse-private/invite/v1",
    actorUserId,
    inviteKind,
    roomId ?? "NO_ROOM",
    clientMutationId,
  ].join("\u001f");
  return base64UrlFromHex(await hmacSha256Hex(inviteDerivationKey, domain));
}

export async function timingSafeSecretEquals(supplied: string, expected: string): Promise<boolean> {
  const suppliedDigest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", encoder.encode(supplied)),
  );
  const expectedDigest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  );
  let difference = 0;
  for (let index = 0; index < expectedDigest.length; index += 1) {
    difference |= suppliedDigest[index]! ^ expectedDigest[index]!;
  }
  return difference === 0;
}
