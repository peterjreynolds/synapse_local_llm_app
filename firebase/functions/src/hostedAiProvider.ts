export interface HostedAiExecutionPolicy {
  dailyRoomRequestLimit: number;
  maximumAttempts: number;
  maximumMonthlyCostMicrousd: number;
  timeoutMillis: number;
}

export interface HostedAiMessage {
  authorId: string;
  authorKind: "HUMAN" | "SYNAPSE_AI";
  body: string;
}

export interface HostedAiRequest {
  messages: HostedAiMessage[];
  policy: HostedAiExecutionPolicy;
  roomId: string;
  sourceMessageId: string;
}

export interface HostedAiResponse {
  body: string;
  providerRequestId: string;
  usage: {
    inputTokens: number;
    outputTokens: number;
  };
}

export interface HostedAiProvider {
  readonly providerId: string;

  generateResponse(request: HostedAiRequest, signal: AbortSignal): Promise<HostedAiResponse>;
}

export const DISABLED_HOSTED_AI_POLICY: Readonly<HostedAiExecutionPolicy> = {
  dailyRoomRequestLimit: 0,
  maximumAttempts: 0,
  maximumMonthlyCostMicrousd: 0,
  timeoutMillis: 30_000,
};

export class DisabledHostedAiProvider implements HostedAiProvider {
  readonly providerId = "UNCONFIGURED";

  async generateResponse(_request: HostedAiRequest, _signal: AbortSignal): Promise<HostedAiResponse> {
    throw new Error(
      "Hosted AI is disabled until an approved provider implementation and Secret Manager credential are configured.",
    );
  }
}
