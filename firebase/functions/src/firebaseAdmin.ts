import {getApps, initializeApp} from "firebase-admin/app";
import {getAuth} from "firebase-admin/auth";
import {getFirestore} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {getStorage} from "firebase-admin/storage";

if (getApps().length === 0) {
  initializeApp();
}

export const firebaseAdminAuth = getAuth();
export const firebaseAdminFirestore = getFirestore();
export const firebaseAdminMessaging = getMessaging();
export const firebaseAdminStorage = getStorage();
export const FIREBASE_FUNCTIONS_REGION = "northamerica-northeast1";
