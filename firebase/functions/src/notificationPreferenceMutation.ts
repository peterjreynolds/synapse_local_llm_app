import {FieldValue} from "firebase-admin/firestore";
import {onCall} from "firebase-functions/v2/https";
import {requireActiveAccount} from "./accountAuthorization.js";
import {FIREBASE_FUNCTIONS_REGION, firebaseAdminFirestore} from "./firebaseAdmin.js";
import {
  DEFAULT_NOTIFICATION_PREFERENCES,
  NotificationPreferences,
  parseNotificationPreferences,
  readNotificationPreferences,
} from "./notificationPreferenceDomain.js";

export const getNotificationPreferences = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<NotificationPreferences> => {
    const {uid} = await requireActiveAccount(request.auth);
    const snapshot = await firebaseAdminFirestore.doc(`notificationPreferences/${uid}`).get();
    return snapshot.exists ? readNotificationPreferences(snapshot.data()) : {...DEFAULT_NOTIFICATION_PREFERENCES};
  },
);

export const updateNotificationPreferences = onCall(
  {region: FIREBASE_FUNCTIONS_REGION},
  async (request): Promise<NotificationPreferences> => {
    const {uid} = await requireActiveAccount(request.auth);
    const preferences = parseNotificationPreferences(request.data);
    await firebaseAdminFirestore.doc(`notificationPreferences/${uid}`).set({
      ...preferences,
      ownerUid: uid,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return preferences;
  },
);
