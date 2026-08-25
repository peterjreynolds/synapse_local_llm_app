export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly publicMessage: string,
  ) {
    super(publicMessage);
    this.name = "HttpError";
  }
}

const RESPONSE_HEADERS = {
  "cache-control": "no-store",
  "content-type": "application/json; charset=utf-8",
};

export function jsonResponse(status: number, body: Readonly<Record<string, unknown>>): Response {
  return new Response(JSON.stringify(body), { status, headers: RESPONSE_HEADERS });
}

export async function readBoundedJson(request: Request, maximumBytes: number): Promise<unknown> {
  if (request.method !== "POST") {
    throw new HttpError(405, "POST is required.");
  }

  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw new HttpError(415, "A JSON request body is required.");
  }

  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    const parsedLength = Number(contentLength);
    if (!Number.isSafeInteger(parsedLength) || parsedLength < 0 || parsedLength > maximumBytes) {
      throw new HttpError(413, "The request body is too large.");
    }
  }

  if (request.body === null) {
    throw new HttpError(400, "The request body is invalid.");
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let byteCount = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    byteCount += value.byteLength;
    if (byteCount > maximumBytes) {
      await reader.cancel();
      throw new HttpError(413, "The request body is too large.");
    }
    chunks.push(value);
  }

  const encoded = new Uint8Array(byteCount);
  let offset = 0;
  for (const chunk of chunks) {
    encoded.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const decoded = new TextDecoder("utf-8", { fatal: true }).decode(encoded);
    return JSON.parse(decoded) as unknown;
  } catch {
    throw new HttpError(400, "The request body is invalid.");
  }
}

export async function serveJsonEndpoint(
  request: Request,
  maximumBytes: number,
  handler: (body: unknown) => Promise<Response>,
): Promise<Response> {
  try {
    return await handler(await readBoundedJson(request, maximumBytes));
  } catch (error: unknown) {
    if (error instanceof HttpError) {
      return jsonResponse(error.status, { error: error.publicMessage });
    }
    return jsonResponse(500, { error: "The request could not be completed." });
  }
}

export function requireBearerToken(request: Request): string {
  const authorization = request.headers.get("authorization") ?? "";
  if (authorization.length > 8192 || !authorization.startsWith("Bearer ")) {
    throw new HttpError(401, "Authentication is required.");
  }
  const token = authorization.slice("Bearer ".length);
  if (token.length < 32 || /\s/.test(token)) {
    throw new HttpError(401, "Authentication is required.");
  }
  return token;
}
