import assert from "node:assert/strict";
import test from "node:test";
import {
  isDedicatedCinderNotificationCandidate,
  shouldNotifyDedicatedCinderAccount,
} from "./cinderNotification.js";

const ACCOUNT_UID = "account_a";
const MESSAGE_ID = `cinder-${"a".repeat(64)}`;
const CINDER_REPLY = {
  accountUid: ACCOUNT_UID,
  aiParticipantId: "participant-cinder-remote-ai",
  aiProvenance: "REMOTE_HOSTED",
  aiProvider: "OPENCLAW_CINDER",
  assistantId: "cinder",
  authorKind: "REMOTE_AI",
  clientMessageId: MESSAGE_ID,
  roomId: "assistant_cinder",
  senderUid: "participant-cinder-remote-ai",
};

test("dedicated Cinder notifications require exact server-owned reply attribution", () => {
  assert.equal(
    isDedicatedCinderNotificationCandidate(CINDER_REPLY, ACCOUNT_UID, MESSAGE_ID),
    true,
  );
  assert.equal(
    isDedicatedCinderNotificationCandidate(
      {...CINDER_REPLY, aiProvider: "UNTRUSTED_PROVIDER"},
      ACCOUNT_UID,
      MESSAGE_ID,
    ),
    false,
  );
  assert.equal(
    isDedicatedCinderNotificationCandidate(
      {...CINDER_REPLY, authorKind: "HUMAN"},
      ACCOUNT_UID,
      MESSAGE_ID,
    ),
    false,
  );
  assert.equal(
    isDedicatedCinderNotificationCandidate(CINDER_REPLY, "another_account", MESSAGE_ID),
    false,
  );
});

test("dedicated Cinder notifications honor active-account and direct-message preferences", () => {
  const activeProfile = {
    accountState: "ACTIVE",
    allowed: true,
    mustChangePassword: false,
  };
  const notificationsDisabled = {
    directMessages: false,
    groupMessages: true,
    mentions: true,
    mutedRooms: false,
  };

  assert.equal(shouldNotifyDedicatedCinderAccount(activeProfile, undefined), true);
  assert.equal(
    shouldNotifyDedicatedCinderAccount(activeProfile, notificationsDisabled),
    false,
  );
  assert.equal(
    shouldNotifyDedicatedCinderAccount({...activeProfile, mustChangePassword: true}, undefined),
    false,
  );
  assert.equal(
    shouldNotifyDedicatedCinderAccount({...activeProfile, accountState: "SUSPENDED"}, undefined),
    false,
  );
});
