import { assertEquals, assertThrows } from "@std/assert";
import {
  assertEmptyObject,
  normalizeUsername,
  parseInviteCode,
  parseIssueInviteRequest,
  parseRedeemAccountInviteRequest,
  parseRegisterDeviceRequest,
  parseSignInRequest,
} from "../_shared/contracts.ts";
import { createServiceClient, generateInternalAccountIdentity } from "../_shared/backend.ts";
import { deriveInviteCode } from "../_shared/crypto.ts";
import { HttpError } from "../_shared/http.ts";

const UUID = "018f1d9e-7b2a-7000-8000-000000000001";
const curveKey = `05${"11".repeat(32)}`;
const signature = "22".repeat(64);
const kyberKey = `08${"33".repeat(1568)}`;

function jwtSegment(payload: Readonly<Record<string, unknown>>): string {
  return btoa(JSON.stringify(payload))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function registration(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    invite_code: "A".repeat(43),
    redemption_id: UUID,
    device_id: UUID,
    username: "private_user",
    password: "correct horse battery staple",
    display_name: "Private User",
    ...overrides,
  };
}

function deviceRegistration(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    device: {
      device_id: UUID,
      protocol_adapter_version: 1,
      registration_id: 16380,
      signal_device_id: 127,
      identity_key_hex: curveKey,
      signed_pre_key: { id: 0xffffff, public_key_hex: curveKey, signature_hex: signature },
      kyber_pre_key: { id: 0xffffff, public_key_hex: kyberKey, signature_hex: signature },
      one_time_pre_key: { id: 0xffffff, public_key_hex: curveKey },
    },
    ...overrides,
  };
}

Deno.test("normalizes the public username before keyed lookup", () => {
  assertEquals(normalizeUsername("  Private_User "), "private_user");
});

Deno.test("binds each generated internal email to its Auth user id", () => {
  const identity = generateInternalAccountIdentity();
  assertEquals(
    identity.internalEmail,
    `${identity.userId}@identity.synapse-private.invalid`,
  );
  assertEquals(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(
      identity.userId,
    ),
    true,
  );
});

Deno.test("isolates a password session from service-role RPC authorization", async () => {
  const userId = "018f1d9e-7b2a-4000-8000-000000000001";
  const sessionId = "018f1d9e-7b2a-4000-8000-000000000002";
  const serviceRoleKey = "test-service-role-key";
  const userAccessToken = [
    jwtSegment({ alg: "HS256", typ: "JWT" }),
    jwtSegment({
      aud: "authenticated",
      exp: Math.floor(Date.now() / 1000) + 3600,
      role: "authenticated",
      session_id: sessionId,
      sub: userId,
    }),
    "test-signature",
  ].join(".");
  const rpcAuthorizations: string[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = new Request(input, init);
    if (request.url.includes("/auth/v1/token?grant_type=password")) {
      return Promise.resolve(
        Response.json({
          access_token: userAccessToken,
          expires_in: 3600,
          refresh_token: "test-refresh-token",
          token_type: "bearer",
          user: {
            id: userId,
            aud: "authenticated",
            role: "authenticated",
            email: `${userId}@identity.synapse-private.invalid`,
            email_confirmed_at: "2026-08-27T00:00:00.000Z",
            app_metadata: { provider: "email", providers: ["email"] },
            user_metadata: {},
            identities: [],
            created_at: "2026-08-27T00:00:00.000Z",
            updated_at: "2026-08-27T00:00:00.000Z",
            is_anonymous: false,
          },
        }),
      );
    }
    if (request.url.includes("/rest/v1/rpc/_edge_reserve_device_registration")) {
      rpcAuthorizations.push(request.headers.get("authorization") ?? "");
      return Promise.resolve(Response.json([]));
    }
    return Promise.reject(new Error(`Unexpected test request: ${request.url}`));
  };

  try {
    const connection = {
      supabaseUrl: "https://example.supabase.co",
      serviceRoleKey,
    };
    const serviceClient = createServiceClient(connection);
    const authenticationClient = createServiceClient(connection);
    const { error: signInError } = await authenticationClient.auth.signInWithPassword({
      email: `${userId}@identity.synapse-private.invalid`,
      password: "correct horse battery staple",
    });
    assertEquals(signInError, null);

    await authenticationClient.rpc("_edge_reserve_device_registration", {});
    await serviceClient.rpc("_edge_reserve_device_registration", {});
    assertEquals(rpcAuthorizations, [
      `Bearer ${userAccessToken}`,
      `Bearer ${serviceRoleKey}`,
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("requires a 32-byte URL-safe invite capability", () => {
  assertEquals(parseInviteCode("A".repeat(43)), "A".repeat(43));
  assertThrows(() => parseInviteCode("A".repeat(42)), HttpError);
  assertThrows(() => parseInviteCode(`${"A".repeat(42)}+`), HttpError);
});

Deno.test("invite issuance requires a mutation id and exact kind-specific shape", () => {
  assertEquals(
    parseIssueInviteRequest({ kind: "ACCOUNT_REGISTRATION", client_mutation_id: UUID }),
    { kind: "ACCOUNT_REGISTRATION", clientMutationId: UUID, expiresInSeconds: 86400 },
  );
  assertEquals(
    parseIssueInviteRequest({
      kind: "ROOM_MEMBERSHIP",
      client_mutation_id: UUID,
      room_id: UUID,
      expires_in_seconds: 300,
    }),
    {
      kind: "ROOM_MEMBERSHIP",
      clientMutationId: UUID,
      roomId: UUID,
      expiresInSeconds: 300,
    },
  );
  assertThrows(
    () => parseIssueInviteRequest({ kind: "ACCOUNT_REGISTRATION" }),
    HttpError,
  );
});

Deno.test("invite derivation is deterministic and domain separated", async () => {
  const key = "ab".repeat(32);
  const first = await deriveInviteCode(key, UUID, "ACCOUNT_REGISTRATION", null, UUID);
  const retry = await deriveInviteCode(key, UUID, "ACCOUNT_REGISTRATION", null, UUID);
  const roomInvite = await deriveInviteCode(key, UUID, "ROOM_MEMBERSHIP", UUID, UUID);
  assertEquals(first, retry);
  assertEquals(first, "6FauAKr3dNIKKgx31fi9RUmhXPsX_bkpkVk2olhMN_E");
  assertEquals(first.length, 43);
  assertEquals(first === roomInvite, false);
});

Deno.test("phase-one account access accepts a transport UUID but no Signal bundle", () => {
  const parsedRegistration = parseRedeemAccountInviteRequest(registration());
  const parsedSignIn = parseSignInRequest({
    device_id: UUID,
    username: "Private_User",
    password: "correct horse battery staple",
  });
  assertEquals(parsedRegistration.deviceId, UUID);
  assertEquals(parsedSignIn.deviceId, UUID);
  assertThrows(
    () => parseRedeemAccountInviteRequest(registration({ device: deviceRegistration().device })),
    HttpError,
  );
  assertThrows(
    () =>
      parseSignInRequest({
        device_id: UUID,
        username: "private_user",
        password: "correct horse battery staple",
        device: deviceRegistration().device,
      }),
    HttpError,
  );
});

Deno.test("register-device accepts the exact Signal wire maxima", () => {
  const parsed = parseRegisterDeviceRequest(deviceRegistration());
  assertEquals(parsed.device.registrationId, 16380);
  assertEquals(parsed.device.signalDeviceId, 127);
  assertEquals(parsed.device.kyberPreKeyPublicHex.length, 3138);
});

Deno.test("rejects invalid Signal wire lengths and prefixes", () => {
  const badIdentity = deviceRegistration();
  (badIdentity.device as Record<string, unknown>).identity_key_hex = `04${"11".repeat(32)}`;
  assertThrows(() => parseRegisterDeviceRequest(badIdentity), HttpError);

  const badKyber = deviceRegistration();
  const kyber = (badKyber.device as Record<string, unknown>).kyber_pre_key as Record<
    string,
    unknown
  >;
  kyber.public_key_hex = `08${"33".repeat(1567)}`;
  assertThrows(() => parseRegisterDeviceRequest(badKyber), HttpError);
});

Deno.test("purge endpoint accepts no caller-controlled batch fields", () => {
  assertEquals(assertEmptyObject({}), undefined);
  assertThrows(() => assertEmptyObject({ batch_size: 1 }), HttpError);
});
