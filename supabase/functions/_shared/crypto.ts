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

export function generateInviteCode(): string {
  const randomBytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const byte of randomBytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
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
