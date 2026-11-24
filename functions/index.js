const crypto = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

initializeApp();

const bootstrapSecret = defineSecret("ADMIN_BOOTSTRAP_TOKEN");

/**
 * One-time first-admin enrollment.
 *
 * The app never contains the bootstrap secret. It is stored in Secret Manager.
 * A transaction permanently binds the bootstrap slot to one Firebase UID.
 * Repeating the call for the same UID is safe (it repairs a failed claim write);
 * a different UID is always rejected after the first successful reservation.
 */
exports.bootstrapFirstAdmin = onCall(
  { region: "us-central1", secrets: [bootstrapSecret] },
  async (request) => {
    if (!request.auth || !request.auth.uid) {
      throw new HttpsError("unauthenticated", "Sign in before admin enrollment.");
    }

    const supplied = String(request.data?.bootstrapToken || "");
    const expected = bootstrapSecret.value();
    const a = Buffer.from(supplied, "utf8");
    const b = Buffer.from(expected || "", "utf8");

    if (!expected || a.length < 24 || a.length !== b.length ||
        !crypto.timingSafeEqual(a, b)) {
      throw new HttpsError("permission-denied", "Invalid bootstrap token.");
    }

    const uid = request.auth.uid;
    const db = getFirestore();
    const ownerRef = db.doc("system/adminBootstrap");

    await db.runTransaction(async (tx) => {
      const snapshot = await tx.get(ownerRef);
      const ownerUid = snapshot.exists ? snapshot.get("uid") : null;

      if (ownerUid && ownerUid !== uid) {
        throw new HttpsError("failed-precondition", "The first admin is already configured.");
      }

      if (!ownerUid) {
        tx.set(ownerRef, {
          uid,
          createdAt: FieldValue.serverTimestamp()
        }, { merge: true });
      }
    });

    const user = await getAuth().getUser(uid);
    await getAuth().setCustomUserClaims(uid, {
      ...(user.customClaims || {}),
      admin: true
    });

    return { success: true };
  }
);
