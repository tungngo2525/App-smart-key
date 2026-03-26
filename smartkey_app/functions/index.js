import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import * as logger from 'firebase-functions/logger';
import admin from 'firebase-admin';

admin.initializeApp();

const db = admin.firestore();

async function fetchTokensForUser(uid) {
  const snapshot = await db.collection('users').doc(uid).collection('tokens').get();
  if (snapshot.empty) return [];
  return snapshot.docs.map((doc) => doc.id);
}

export const notifyShareCreated = onDocumentCreated('shares/{shareId}', async (event) => {
  const data = event.data?.data();
  if (!data) return;
  const tokens = await fetchTokensForUser(data.granteeUid);
  if (!tokens.length) {
    logger.info('No tokens for grantee', data.granteeUid);
    return;
  }
  const message = {
    notification: {
      title: 'Bạn vừa nhận được SmartKey',
      body: `${data.ownerUsername || 'Người dùng'} đã chia sẻ xe ${data.vehicleName}`
    },
    tokens
  };
  await admin.messaging().sendEachForMulticast(message);
});

export const remindShareExpiring = onSchedule('every 60 minutes', async () => {
  const threshold = Date.now() + 2 * 60 * 60 * 1000; // 2 giờ tới
  const query = await db.collection('shares')
    .where('status', '==', 'active')
    .where('expiresAt', '<=', threshold)
    .get();
  if (query.empty) return;
  for (const doc of query.docs) {
    const data = doc.data();
    if (data.reminderSentAt) continue;
    const tokens = await fetchTokensForUser(data.granteeUid);
    if (tokens.length === 0) continue;
    await admin.messaging().sendEachForMulticast({
      notification: {
        title: 'SmartKey sắp hết hạn',
        body: `Quyền mở xe ${data.vehicleName} sẽ hết hạn sớm.`
      },
      tokens
    });
    await doc.ref.update({
      reminderSentAt: admin.firestore.FieldValue.serverTimestamp()
    });
  }
});

export const cleanupExpiredShares = onSchedule('every 24 hours', async () => {
  const now = Date.now();
  const query = await db.collection('shares')
    .where('status', '==', 'active')
    .where('expiresAt', '<=', now)
    .get();
  if (query.empty) return;
  const batch = db.batch();
  for (const doc of query.docs) {
    const data = doc.data();
    batch.update(doc.ref, { status: 'revoked', cleanedAt: admin.firestore.FieldValue.serverTimestamp() });
    const lookupRef = db.collection('shareLookup')
      .doc(data.vehicleId)
      .collection('recipients')
      .doc(data.granteeUid);
    batch.delete(lookupRef);
  }
  await batch.commit();
});
