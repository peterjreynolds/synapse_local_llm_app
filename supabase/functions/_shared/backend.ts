import { createClient, type Session, type SupabaseClient, type User } from "@supabase/supabase-js";
import type { DeviceRegistration } from "./contracts.ts";
import { parseUuid } from "./contracts.ts";
import { hmacSha256Hex, postgresByteaFromHex } from "./crypto.ts";
import { HttpError, requireBearerToken } from "./http.ts";

export interface VerifiedActor {
  readonly accessToken: string;
  readonly userId: string;
  readonly authSessionId: string;
}

export interface PublicSession {
  readonly access_token: string;
  readonly refresh_token: string;
  readonly expires_at: number | null;
  readonly expires_in: number;
  readonly token_type: string;
}

export interface RuntimeSecrets {
  readonly supabaseUrl: string;
  readonly serviceRoleKey: string;
  readonly usernamePepper: string;
  readonly rateLimitPepper: string;
  readonly purgeSecret: string;
}

export interface DeviceRegistrationReservation {
  readonly userId: string;
  readonly deviceId: string;
  readonly signalDeviceId: number;
  readonly expiresAt: string;
}

interface SupabaseAdminConnection {
  readonly supabaseUrl: string;
  readonly serviceRoleKey: string;
}

function requireEnvironmentVariable(name: string): string {
  const value = Deno.env.get(name);
  if (value === undefined || value.length === 0) {
    throw new Error(`Required runtime secret ${name} is unavailable`);
  }
  return value;
}

function readDefaultSecretKey(): string {
  const legacyServiceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacyServiceRoleKey !== undefined && legacyServiceRoleKey.length > 0) {
    return legacyServiceRoleKey;
  }

  const encodedSecretKeys = requireEnvironmentVariable("SUPABASE_SECRET_KEYS");
  try {
    const parsedSecretKeys: unknown = JSON.parse(encodedSecretKeys);
    if (
      typeof parsedSecretKeys !== "object" || parsedSecretKeys === null ||
      Array.isArray(parsedSecretKeys)
    ) {
      throw new Error("secret key collection is not an object");
    }
    const defaultSecretKey = (parsedSecretKeys as Record<string, unknown>).default;
    if (typeof defaultSecretKey !== "string" || defaultSecretKey.length < 32) {
      throw new Error("default secret key is unavailable");
    }
    return defaultSecretKey;
  } catch {
    throw new Error("The default Supabase secret key is unavailable");
  }
}

function readSupabaseAdminConnection(): SupabaseAdminConnection {
  const supabaseUrl = requireEnvironmentVariable("SUPABASE_URL");
  const parsedUrl = new URL(supabaseUrl);
  if (
    parsedUrl.protocol !== "https:" &&
    !(
      parsedUrl.protocol === "http:" &&
      ["127.0.0.1", "localhost", "kong"].includes(parsedUrl.hostname)
    )
  ) {
    throw new Error("SUPABASE_URL must use HTTPS outside local development");
  }

  return {
    supabaseUrl: parsedUrl.toString().replace(/\/$/u, ""),
    serviceRoleKey: readDefaultSecretKey(),
  };
}

export function createServiceClient(connection: SupabaseAdminConnection): SupabaseClient {
  return createClient(connection.supabaseUrl, connection.serviceRoleKey, {
    auth: {
      autoRefreshToken: false,
      detectSessionInUrl: false,
      persistSession: false,
    },
  });
}

function expectHexSecret(row: Readonly<Record<string, unknown>>, field: string): string {
  const secret = expectStringField(row, field);
  if (!/^[0-9a-f]{64}$/u.test(secret)) {
    throw new Error(`Backend runtime secret field ${field} is malformed`);
  }
  return secret;
}

export async function readRuntimeSecrets(): Promise<RuntimeSecrets> {
  const connection = readSupabaseAdminConnection();
  const serviceClient = createServiceClient(connection);
  const { data, error } = await serviceClient.rpc("_edge_initialize_runtime_configuration", {
    p_project_url: connection.supabaseUrl,
  });
  if (error !== null) throw new Error("Backend runtime configuration is unavailable");
  const row = expectSingleObject(data);
  const purgeSecret = expectStringField(row, "purge_secret");
  if (!/^[A-Za-z0-9_-]{43}$/u.test(purgeSecret)) {
    throw new Error("Backend runtime purge capability is malformed");
  }
  return {
    ...connection,
    usernamePepper: expectHexSecret(row, "username_hmac_pepper"),
    rateLimitPepper: expectHexSecret(row, "rate_limit_hmac_pepper"),
    purgeSecret,
  };
}

function decodeJwtPayload(accessToken: string): Record<string, unknown> {
  const segments = accessToken.split(".");
  const encodedPayload = segments[1];
  if (segments.length !== 3 || encodedPayload === undefined) {
    throw new HttpError(401, "Authentication is required.");
  }
  try {
    const base64 = encodedPayload.replaceAll("-", "+").replaceAll("_", "/");
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const parsed: unknown = JSON.parse(atob(padded));
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new Error("JWT payload is not an object");
    }
    return parsed as Record<string, unknown>;
  } catch {
    throw new HttpError(401, "Authentication is required.");
  }
}

function isPermanentUser(user: User): boolean {
  const anonymousFlag = (user as User & { is_anonymous?: unknown }).is_anonymous;
  return anonymousFlag !== true;
}

export async function verifyActor(
  request: Request,
  serviceClient: SupabaseClient,
): Promise<VerifiedActor> {
  const accessToken = requireBearerToken(request);
  const { data, error } = await serviceClient.auth.getUser(accessToken);
  if (error !== null || data.user === null || !isPermanentUser(data.user)) {
    throw new HttpError(401, "Authentication is required.");
  }

  const payload = decodeJwtPayload(accessToken);
  const userId = parseUuid(data.user.id);
  if (payload.sub !== userId) {
    throw new HttpError(401, "Authentication is required.");
  }
  const authSessionId = parseUuid(payload.session_id);
  return { accessToken, userId, authSessionId };
}

export function publicSession(session: Session): PublicSession {
  return {
    access_token: session.access_token,
    refresh_token: session.refresh_token,
    expires_at: session.expires_at ?? null,
    expires_in: session.expires_in,
    token_type: session.token_type,
  };
}

export async function reserveDeviceRegistrationForSession(
  serviceClient: SupabaseClient,
  userId: string,
  session: Session,
  deviceId: string,
): Promise<DeviceRegistrationReservation> {
  const normalizedUserId = parseUuid(userId);
  const normalizedDeviceId = parseUuid(deviceId);
  const authSessionId = parseUuid(decodeJwtPayload(session.access_token).session_id);
  const { data, error } = await serviceClient.rpc("_edge_reserve_device_registration", {
    p_user_id: normalizedUserId,
    p_auth_session_id: authSessionId,
    p_device_id: normalizedDeviceId,
  });
  if (error !== null) throw new Error("Device registration reservation failed");
  const receipt = expectSingleObject(data);
  const receiptUserId = parseUuid(expectStringField(receipt, "user_id"));
  const receiptDeviceId = parseUuid(expectStringField(receipt, "device_id"));
  const signalDeviceId = expectIntegerField(receipt, "signal_device_id");
  if (
    receiptUserId !== normalizedUserId || receiptDeviceId !== normalizedDeviceId ||
    signalDeviceId < 1 || signalDeviceId > 127
  ) {
    throw new Error("Device registration reservation receipt was inconsistent");
  }
  return {
    userId: receiptUserId,
    deviceId: receiptDeviceId,
    signalDeviceId,
    expiresAt: expectStringField(receipt, "expires_at"),
  };
}

export function generateInternalAccountEmail(): string {
  return `${crypto.randomUUID()}@identity.synapse-private.invalid`;
}

export function deviceRegistrationRpcArguments(
  device: DeviceRegistration,
): Readonly<Record<string, unknown>> {
  return {
    p_device_id: device.deviceId,
    p_protocol_adapter_version: device.protocolAdapterVersion,
    p_registration_id: device.registrationId,
    p_signal_device_id: device.signalDeviceId,
    p_identity_key: postgresByteaFromHex(device.identityKeyHex),
    p_signed_pre_key_id: device.signedPreKeyId,
    p_signed_pre_key_public: postgresByteaFromHex(device.signedPreKeyPublicHex),
    p_signed_pre_key_signature: postgresByteaFromHex(device.signedPreKeySignatureHex),
    p_kyber_pre_key_id: device.kyberPreKeyId,
    p_kyber_pre_key_public: postgresByteaFromHex(device.kyberPreKeyPublicHex),
    p_kyber_pre_key_signature: postgresByteaFromHex(device.kyberPreKeySignatureHex),
    p_one_time_pre_key_id: device.oneTimePreKeyId,
    p_one_time_pre_key_public: device.oneTimePreKeyPublicHex === null
      ? null
      : postgresByteaFromHex(device.oneTimePreKeyPublicHex),
  };
}

function requestSource(request: Request): string {
  const candidate = request.headers.get("cf-connecting-ip") ??
    request.headers.get("x-forwarded-for")?.split(",", 1)[0] ??
    request.headers.get("x-real-ip") ??
    "unavailable";
  const normalized = candidate.trim().toLowerCase();
  return normalized.length >= 1 && normalized.length <= 200 && /^[0-9a-f:.]+$/u.test(normalized)
    ? normalized
    : "unavailable";
}

export async function enforceAccountAccessRateLimit(
  serviceClient: SupabaseClient,
  request: Request,
  operation: "REGISTER" | "SIGN_IN" | "ROOM_REDEEM",
  rateLimitPepper: string,
): Promise<void> {
  const sourceDigest = await hmacSha256Hex(
    rateLimitPepper,
    `${operation}\u0000${requestSource(request)}`,
  );
  const { data, error } = await serviceClient.rpc("_edge_record_account_access_attempt", {
    p_source_digest: postgresByteaFromHex(sourceDigest),
    p_operation: operation,
  });
  if (error !== null) throw new Error("The rate-limit receipt could not be persisted");
  const row = expectSingleObject(data);
  if (row.accepted !== true) {
    throw new HttpError(429, "Too many attempts. Try again later.");
  }
}

export async function usernameDigest(
  usernamePepper: string,
  normalizedUsername: string,
): Promise<string> {
  return await hmacSha256Hex(
    usernamePepper,
    `synapse-private/username/v1\u0000${normalizedUsername}`,
  );
}

export function expectSingleObject(input: unknown): Record<string, unknown> {
  if (!Array.isArray(input) || input.length !== 1) {
    throw new Error("The backend did not return exactly one receipt");
  }
  const row: unknown = input[0];
  if (typeof row !== "object" || row === null || Array.isArray(row)) {
    throw new Error("The backend returned a malformed receipt");
  }
  return row as Record<string, unknown>;
}

export function expectObjectRows(input: unknown): ReadonlyArray<Record<string, unknown>> {
  if (!Array.isArray(input)) throw new Error("The backend returned malformed rows");
  return input.map((row: unknown) => {
    if (typeof row !== "object" || row === null || Array.isArray(row)) {
      throw new Error("The backend returned a malformed row");
    }
    return row as Record<string, unknown>;
  });
}

export function expectStringField(row: Readonly<Record<string, unknown>>, field: string): string {
  const value = row[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`Backend receipt field ${field} is malformed`);
  }
  return value;
}

export function expectIntegerField(row: Readonly<Record<string, unknown>>, field: string): number {
  const value = row[field];
  if (!Number.isSafeInteger(value)) throw new Error(`Backend receipt field ${field} is malformed`);
  return value as number;
}
