export interface MessageRecipientAuthorizationState {
  membershipActive: boolean;
  notificationsEnabled: boolean;
  profileAllowed: boolean;
  uid: string;
}

export function selectAuthorizedMessageRecipientUids(
  candidateUids: readonly string[],
  authorizationStates: readonly MessageRecipientAuthorizationState[],
): string[] {
  const authorizationByUid = new Map(
    authorizationStates.map((authorizationState) => [authorizationState.uid, authorizationState]),
  );
  return [...new Set(candidateUids)].filter((uid) => {
    const authorizationState = authorizationByUid.get(uid);
    return authorizationState?.profileAllowed === true &&
      authorizationState.membershipActive === true &&
      authorizationState.notificationsEnabled === true;
  });
}
