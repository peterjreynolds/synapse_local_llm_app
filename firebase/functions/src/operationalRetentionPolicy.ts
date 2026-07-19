export interface RetentionPolicy {
  collectionName: string;
  retentionMillis: number;
  timestampField: string;
}

export const DAY_MILLIS = 24 * 60 * 60 * 1_000;

export const OPERATIONAL_RETENTION_POLICIES = [
  {collectionName: "callableRateLimits", retentionMillis: 2 * DAY_MILLIS, timestampField: "windowStartedAt"},
  {collectionName: "registrationRateLimits", retentionMillis: 2 * DAY_MILLIS, timestampField: "windowStartedAt"},
  {collectionName: "registrationReservations", retentionMillis: 30 * DAY_MILLIS, timestampField: "createdAt"},
  {collectionName: "invitations", retentionMillis: 30 * DAY_MILLIS, timestampField: "expiresAt"},
  {collectionName: "notificationDeliveries", retentionMillis: 30 * DAY_MILLIS, timestampField: "startedAt"},
  {collectionName: "callSessions", retentionMillis: 30 * DAY_MILLIS, timestampField: "createdAt"},
  {collectionName: "cinderAuditEvents", retentionMillis: 90 * DAY_MILLIS, timestampField: "createdAt"},
  {collectionName: "cinderResponseAudits", retentionMillis: 90 * DAY_MILLIS, timestampField: "completedAt"},
  {collectionName: "remoteAiAuditEvents", retentionMillis: 90 * DAY_MILLIS, timestampField: "createdAt"},
  {collectionName: "remoteAiResponseAudits", retentionMillis: 90 * DAY_MILLIS, timestampField: "completedAt"},
  {collectionName: "inviteRedemptions", retentionMillis: 180 * DAY_MILLIS, timestampField: "redeemedAt"},
  {collectionName: "securityAuditEvents", retentionMillis: 365 * DAY_MILLIS, timestampField: "createdAt"},
] as const satisfies readonly RetentionPolicy[];

export const OPERATIONAL_COLLECTION_GROUP_RETENTION_POLICIES = [
  {collectionName: "signals", retentionMillis: 30 * DAY_MILLIS, timestampField: "createdAt"},
] as const satisfies readonly RetentionPolicy[];
