import { HttpError } from "./http.ts";

export interface DeviceRegistration {
  readonly deviceId: string;
  readonly protocolAdapterVersion: 1;
  readonly registrationId: number;
  readonly signalDeviceId: number;
  readonly identityKeyHex: string;
  readonly signedPreKeyId: number;
  readonly signedPreKeyPublicHex: string;
  readonly signedPreKeySignatureHex: string;
  readonly kyberPreKeyId: number;
  readonly kyberPreKeyPublicHex: string;
  readonly kyberPreKeySignatureHex: string;
  readonly oneTimePreKeyId: number | null;
  readonly oneTimePreKeyPublicHex: string | null;
}

export interface RedeemAccountInviteRequest {
  readonly inviteCode: string;
  readonly redemptionId: string;
  readonly deviceId: string;
  readonly username: string;
  readonly password: string;
  readonly displayName: string;
}

export interface SignInRequest {
  readonly deviceId: string;
  readonly username: string;
  readonly password: string;
}

export interface RegisterDeviceRequest {
  readonly device: DeviceRegistration;
}

export type IssueInviteRequest =
  | {
    readonly kind: "ACCOUNT_REGISTRATION";
    readonly clientMutationId: string;
    readonly expiresInSeconds: number;
  }
  | {
    readonly kind: "ROOM_MEMBERSHIP";
    readonly clientMutationId: string;
    readonly roomId: string;
    readonly expiresInSeconds: number;
  };

export interface RedeemRoomInviteRequest {
  readonly inviteCode: string;
  readonly redemptionId: string;
}

type JsonObject = Record<string, unknown>;

function parseObject(
  input: unknown,
  requiredKeys: readonly string[],
  optionalKeys: readonly string[] = [],
): JsonObject {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new HttpError(400, "The request body is invalid.");
  }
  const object = input as JsonObject;
  const allowed = new Set([...requiredKeys, ...optionalKeys]);
  if (
    !requiredKeys.every((key) => Object.hasOwn(object, key)) ||
    Object.keys(object).some((key) => !allowed.has(key))
  ) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return object;
}

function parseString(input: unknown, minimumLength: number, maximumLength: number): string {
  if (typeof input !== "string") throw new HttpError(400, "The request body is invalid.");
  const codePointLength = [...input].length;
  if (codePointLength < minimumLength || codePointLength > maximumLength) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return input;
}

function parseInteger(input: unknown, minimum: number, maximum: number): number {
  if (!Number.isSafeInteger(input) || (input as number) < minimum || (input as number) > maximum) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return input as number;
}

export function parseUuid(input: unknown): string {
  const value = parseString(input, 36, 36).toLowerCase();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return value;
}

export function normalizeUsername(input: unknown): string {
  const value = parseString(input, 3, 64).trim().toLowerCase();
  if (!/^[a-z][a-z0-9_]{2,31}$/.test(value)) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return value;
}

function parsePassword(input: unknown): string {
  const value = parseString(input, 8, 128);
  const byteLength = new TextEncoder().encode(value).byteLength;
  const containsControlCharacter = [...value].some((character) => {
    const codePoint = character.codePointAt(0)!;
    return codePoint <= 0x1f || codePoint === 0x7f;
  });
  if (byteLength < 8 || byteLength > 128 || containsControlCharacter) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return value;
}

function parseDisplayName(input: unknown): string {
  const value = parseString(input, 1, 64);
  if (value.trim() !== value || /\p{Cc}/u.test(value)) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return value;
}

export function parseInviteCode(input: unknown): string {
  const value = parseString(input, 43, 43);
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return value;
}

function parseHexBytes(
  input: unknown,
  minimumBytes: number,
  maximumBytes: number,
  requiredFirstByte?: number,
): string {
  const value = parseString(input, minimumBytes * 2, maximumBytes * 2);
  if (!/^[0-9a-f]+$/.test(value) || value.length % 2 !== 0) {
    throw new HttpError(400, "The request body is invalid.");
  }
  const byteLength = value.length / 2;
  if (byteLength < minimumBytes || byteLength > maximumBytes) {
    throw new HttpError(400, "The request body is invalid.");
  }
  if (
    requiredFirstByte !== undefined &&
    value.slice(0, 2) !== requiredFirstByte.toString(16).padStart(2, "0")
  ) {
    throw new HttpError(400, "The request body is invalid.");
  }
  return value;
}

function parseDeviceRegistration(input: unknown): DeviceRegistration {
  const object = parseObject(input, [
    "device_id",
    "protocol_adapter_version",
    "registration_id",
    "signal_device_id",
    "identity_key_hex",
    "signed_pre_key",
    "kyber_pre_key",
  ], ["one_time_pre_key"]);
  const signedPreKey = parseObject(object.signed_pre_key, [
    "id",
    "public_key_hex",
    "signature_hex",
  ]);
  const kyberPreKey = parseObject(object.kyber_pre_key, ["id", "public_key_hex", "signature_hex"]);
  const oneTimePreKey = Object.hasOwn(object, "one_time_pre_key")
    ? parseObject(object.one_time_pre_key, ["id", "public_key_hex"])
    : null;
  const protocolAdapterVersion = parseInteger(object.protocol_adapter_version, 1, 1);

  return {
    deviceId: parseUuid(object.device_id),
    protocolAdapterVersion: protocolAdapterVersion as 1,
    registrationId: parseInteger(object.registration_id, 1, 16380),
    signalDeviceId: parseInteger(object.signal_device_id, 1, 127),
    identityKeyHex: parseHexBytes(object.identity_key_hex, 33, 33, 5),
    signedPreKeyId: parseInteger(signedPreKey.id, 0, 0xffffff),
    signedPreKeyPublicHex: parseHexBytes(signedPreKey.public_key_hex, 33, 33, 5),
    signedPreKeySignatureHex: parseHexBytes(signedPreKey.signature_hex, 64, 64),
    kyberPreKeyId: parseInteger(kyberPreKey.id, 0, 0xffffff),
    kyberPreKeyPublicHex: parseHexBytes(kyberPreKey.public_key_hex, 1569, 1569, 8),
    kyberPreKeySignatureHex: parseHexBytes(kyberPreKey.signature_hex, 64, 64),
    oneTimePreKeyId: oneTimePreKey === null ? null : parseInteger(oneTimePreKey.id, 0, 0xffffff),
    oneTimePreKeyPublicHex: oneTimePreKey === null
      ? null
      : parseHexBytes(oneTimePreKey.public_key_hex, 33, 33, 5),
  };
}

export function parseRedeemAccountInviteRequest(input: unknown): RedeemAccountInviteRequest {
  const object = parseObject(input, [
    "invite_code",
    "redemption_id",
    "device_id",
    "username",
    "password",
    "display_name",
  ]);
  return {
    inviteCode: parseInviteCode(object.invite_code),
    redemptionId: parseUuid(object.redemption_id),
    deviceId: parseUuid(object.device_id),
    username: normalizeUsername(object.username),
    password: parsePassword(object.password),
    displayName: parseDisplayName(object.display_name),
  };
}

export function parseSignInRequest(input: unknown): SignInRequest {
  const object = parseObject(input, ["device_id", "username", "password"]);
  return {
    deviceId: parseUuid(object.device_id),
    username: normalizeUsername(object.username),
    password: parsePassword(object.password),
  };
}

export function parseRegisterDeviceRequest(input: unknown): RegisterDeviceRequest {
  const object = parseObject(input, ["device"]);
  return { device: parseDeviceRegistration(object.device) };
}

export function parseIssueInviteRequest(input: unknown): IssueInviteRequest {
  const object = parseObject(
    input,
    ["kind", "client_mutation_id"],
    ["room_id", "expires_in_seconds"],
  );
  const kind = parseString(object.kind, 1, 32);
  const clientMutationId = parseUuid(object.client_mutation_id);
  const expiresInSeconds = Object.hasOwn(object, "expires_in_seconds")
    ? parseInteger(object.expires_in_seconds, 60, 86400)
    : 86400;
  if (kind === "ACCOUNT_REGISTRATION" && !Object.hasOwn(object, "room_id")) {
    return { kind, clientMutationId, expiresInSeconds };
  }
  if (kind === "ROOM_MEMBERSHIP" && Object.hasOwn(object, "room_id")) {
    return { kind, clientMutationId, roomId: parseUuid(object.room_id), expiresInSeconds };
  }
  throw new HttpError(400, "The request body is invalid.");
}

export function parseRedeemRoomInviteRequest(input: unknown): RedeemRoomInviteRequest {
  const object = parseObject(input, ["invite_code", "redemption_id"]);
  return {
    inviteCode: parseInviteCode(object.invite_code),
    redemptionId: parseUuid(object.redemption_id),
  };
}

export function assertEmptyObject(input: unknown): void {
  parseObject(input, []);
}
