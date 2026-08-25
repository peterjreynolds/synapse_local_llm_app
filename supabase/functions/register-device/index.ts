import {
  createServiceClient,
  deviceRegistrationRpcArguments,
  expectIntegerField,
  expectSingleObject,
  expectStringField,
  readRuntimeSecrets,
  verifyActor,
} from "../_shared/backend.ts";
import { parseRegisterDeviceRequest } from "../_shared/contracts.ts";
import { HttpError, jsonResponse, serveJsonEndpoint } from "../_shared/http.ts";

Deno.serve((request: Request) =>
  serveJsonEndpoint(request, 32 * 1024, async (body: unknown) => {
    const registration = parseRegisterDeviceRequest(body);
    const secrets = await readRuntimeSecrets();
    const serviceClient = createServiceClient(secrets);
    const actor = await verifyActor(request, serviceClient);

    try {
      const { data, error } = await serviceClient.rpc("_edge_register_device_session", {
        p_user_id: actor.userId,
        p_auth_session_id: actor.authSessionId,
        ...deviceRegistrationRpcArguments(registration.device),
      });
      if (error !== null) throw new Error("Device registration failed");
      const receipt = expectSingleObject(data);
      return jsonResponse(200, {
        device_registration: {
          user_id: expectStringField(receipt, "user_id"),
          device_id: expectStringField(receipt, "device_id"),
          signal_device_id: expectIntegerField(receipt, "signal_device_id"),
          display_name: expectStringField(receipt, "display_name"),
          bound_at: expectStringField(receipt, "bound_at"),
        },
      });
    } catch {
      throw new HttpError(403, "The device could not be registered.");
    }
  })
);
