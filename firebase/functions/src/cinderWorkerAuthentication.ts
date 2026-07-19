import {createHash, timingSafeEqual} from "node:crypto";

export type CinderWorkerAuthorizationVerifier = (authorizationHeader: string | undefined) => boolean;

export function createCinderWorkerAuthorizationVerifier(
  expectedSecret: string,
): CinderWorkerAuthorizationVerifier {
  const expectedDigest = digestWorkerSecret(requireWorkerSecret(expectedSecret));
  return (authorizationHeader) => {
    const presentedSecret = readBearerSecret(authorizationHeader);
    if (presentedSecret === null) return false;
    return timingSafeEqual(expectedDigest, digestWorkerSecret(presentedSecret));
  };
}

function readBearerSecret(authorizationHeader: string | undefined): string | null {
  if (typeof authorizationHeader !== "string") return null;
  const match = /^Bearer ([\x21-\x7e]{32,512})$/.exec(authorizationHeader);
  return match?.[1] ?? null;
}

function requireWorkerSecret(secret: string): string {
  if (!/^[\x21-\x7e]{32,512}$/.test(secret)) {
    throw new Error("The Cinder worker secret must contain 32-512 visible ASCII characters.");
  }
  return secret;
}

function digestWorkerSecret(secret: string): Buffer {
  return createHash("sha256").update(secret, "utf8").digest();
}
