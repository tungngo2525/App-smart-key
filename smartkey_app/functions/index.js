import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { onCall, onRequest, HttpsError } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import * as logger from 'firebase-functions/logger';
import admin from 'firebase-admin';
import crypto from 'node:crypto';

admin.initializeApp();

const db = admin.firestore();
const SERVER_TIMESTAMP = admin.firestore.FieldValue.serverTimestamp();
const MAX_SYNC_EVENTS = 100;
const CREDENTIAL_PACKAGE_VERSION = 1;
const ISSUER_ID = 'smartkey-dev-issuer-v1';
const ISSUER_SIGNATURE_ALGORITHM = 'ECDSA_P256_SHA256';
const DEFAULT_ISSUER_PRIVATE_KEY_PEM = `-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgtQCLMKnNqB/r5zrH
am4sipsXns6JZCke6ezlaldsXH6hRANCAATe9rko/h9dIX3sro0xgay7xNVSb5j9
cHjgKvEbS358Yb9BvKMy2wI8nxQT6aLWvGiJBoBj2RrG1kSqYONs5fDg
-----END PRIVATE KEY-----`;

const ROLE_CONFIG = {
  FULL: {
    role: 'FULL',
    permissionMask: 0xFF,
    permissions: { lock: true, unlock: true, trunk: true, admin: true }
  },
  VALET: {
    role: 'VALET',
    permissionMask: 0x03,
    permissions: { lock: true, unlock: true, trunk: false, admin: false }
  },
  UNLOCK_ONLY: {
    role: 'UNLOCK_ONLY',
    permissionMask: 0x01,
    permissions: { lock: false, unlock: true, trunk: false, admin: false }
  },
  LOCK_ONLY: {
    role: 'LOCK_ONLY',
    permissionMask: 0x02,
    permissions: { lock: true, unlock: false, trunk: false, admin: false }
  }
};

function normalizeRole(value) {
  const raw = String(value || 'VALET').trim().toUpperCase();
  if (raw === 'FULL' || raw === 'PERM_FULL') return 'FULL';
  if (raw === 'VALET' || raw === 'PERM_VALET') return 'VALET';
  if (raw === 'UNLOCK_ONLY' || raw === 'UNLOCK' || raw === 'PERM_UNLOCK') return 'UNLOCK_ONLY';
  if (raw === 'LOCK_ONLY' || raw === 'LOCK' || raw === 'PERM_LOCK') return 'LOCK_ONLY';
  return 'VALET';
}

function roleConfigFromMask(mask) {
  switch (Number(mask) & 0xFF) {
    case 0x01: return ROLE_CONFIG.UNLOCK_ONLY;
    case 0x02: return ROLE_CONFIG.LOCK_ONLY;
    case 0x03: return ROLE_CONFIG.VALET;
    default: return ROLE_CONFIG.FULL;
  }
}

function normalizePublicKeyHex(value) {
  const hex = String(value || '').trim().toUpperCase();
  if (hex.length !== 130 || !hex.startsWith('04') || /[^0-9A-F]/.test(hex)) {
    throw new HttpsError('failed-precondition', 'Target device has no valid digital key material');
  }
  return hex;
}

function getIssuerPrivateKeyPem() {
  return String(process.env.SMARTKEY_ISSUER_PRIVATE_KEY_PEM || DEFAULT_ISSUER_PRIVATE_KEY_PEM)
    .replace(/\\n/g, '\n')
    .trim();
}

function buildCredentialSigningPayload(fields = {}) {
  const permissionMask = Number(fields.permissionMask || 0);
  const expiresAt = Number(fields.expiresAt || 0);
  const publicKeyHex = String(fields.publicKeyHex || '').trim().toUpperCase();
  return [
    'CCC-CLOUD-CRED-V1',
    String(fields.credentialId || '').trim(),
    String(fields.subjectUid || '').trim(),
    String(fields.subjectDeviceId || '').trim(),
    publicKeyHex,
    String(permissionMask),
    String(expiresAt)
  ].join('\n');
}

function signCredentialPayload(fields = {}) {
  const signer = crypto.createSign('SHA256');
  signer.update(buildCredentialSigningPayload(fields), 'utf8');
  signer.end();
  return signer.sign(getIssuerPrivateKeyPem()).toString('hex').toUpperCase();
}

function getVehicleSummary(vehicleData = {}) {
  const profile = vehicleData.profile || {};
  const tb = vehicleData.tb || {};
  return {
    vehicleName: String(profile.name || ''),
    vehiclePlate: String(profile.plate || ''),
    ecuVehicleId: String(profile.ecuVehicleId || ''),
    vehicleLocation: String(profile.location || ''),
    vehicleActive: Boolean(profile.active ?? true),
    vehicleLatitude: Number(profile.latitude || 0),
    vehicleLongitude: Number(profile.longitude || 0),
    tbDeviceId: String(tb.deviceId || '')
  };
}

async function ensureVehicleSyncIdentity(ref, data = {}, preferredSyncId = '') {
  const profile = data.profile || {};
  const currentTb = data.tb || {};
  const fallbackSyncId = String(
    currentTb.deviceId || preferredSyncId || profile.ecuVehicleId || ref?.id || ''
  ).trim().toUpperCase();

  const nextTb = {
    ...currentTb,
    deviceId: String(currentTb.deviceId || fallbackSyncId || '').trim(),
    syncToken: String(currentTb.syncToken || fallbackSyncId || '').trim(),
    syncMode: 'CLOUD_SYNC'
  };

  const changed =
    String(currentTb.deviceId || '').trim() !== nextTb.deviceId ||
    String(currentTb.syncToken || '').trim() !== nextTb.syncToken ||
    String(currentTb.syncMode || '').trim() !== nextTb.syncMode;

  if (changed && ref) {
    await ref.set({ tb: nextTb }, { merge: true });
  }

  return {
    ...data,
    tb: nextTb
  };
}

async function fetchTokensForUser(uid) {
  const snapshot = await db.collection('users').doc(uid).collection('tokens').get();
  if (snapshot.empty) return [];
  return snapshot.docs.map((doc) => doc.id);
}

async function getVehicleContext(vehicleId, ecuVehicleId = '', tbDeviceId = '') {
  const normalizedVehicleId = String(vehicleId || '').trim();
  const normalizedEcuVehicleId = String(ecuVehicleId || '').trim().toUpperCase();
  const normalizedTbDeviceId = String(tbDeviceId || '').trim();

  let snap = null;
  let ref = null;

  if (normalizedVehicleId) {
    ref = db.collection('vehicles').doc(normalizedVehicleId);
    snap = await ref.get();
  }

  if ((!snap || !snap.exists) && normalizedEcuVehicleId) {
    const query = await db.collection('vehicles')
      .where('profile.ecuVehicleId', '==', normalizedEcuVehicleId)
      .limit(1)
      .get();
    snap = query.docs[0] || null;
    ref = snap?.ref || null;
  }

  if ((!snap || !snap.exists) && normalizedTbDeviceId) {
    const query = await db.collection('vehicles')
      .where('tb.deviceId', '==', normalizedTbDeviceId)
      .limit(1)
      .get();
    snap = query.docs[0] || null;
    ref = snap?.ref || null;
  }

  if (!snap || !snap.exists || !ref) {
    throw new HttpsError('not-found', 'Vehicle not found');
  }

  const hydratedData = await ensureVehicleSyncIdentity(ref, snap.data() || {}, normalizedEcuVehicleId || normalizedTbDeviceId);
  const profile = hydratedData.profile || {};
  const tb = hydratedData.tb || {};
  return {
    ref,
    snap,
    data: hydratedData,
    profile,
    tb,
    ownerUid: String(profile.ownerUid || hydratedData.ownerUid || '')
  };
}

async function resolveTargetUser(targetUid, targetEmail) {
  if (targetUid) {
    const snap = await db.collection('users').doc(String(targetUid).trim()).get();
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Target user not found');
    }
    return { uid: snap.id, ...(snap.data() || {}) };
  }

  const normalizedEmail = String(targetEmail || '').trim().toLowerCase();
  if (!normalizedEmail) {
    throw new HttpsError('invalid-argument', 'targetUid or targetEmail is required');
  }

  const query = await db.collection('users')
    .where('emailLower', '==', normalizedEmail)
    .limit(1)
    .get();

  if (query.empty) {
    throw new HttpsError('not-found', `No user found for ${normalizedEmail}`);
  }

  const doc = query.docs[0];
  return { uid: doc.id, ...(doc.data() || {}) };
}

async function resolveTargetDevice(targetUid, preferredDeviceId) {
  const userRef = db.collection('users').doc(targetUid);
  const devicesRef = userRef.collection('devices');

  if (preferredDeviceId) {
    const preferredSnap = await devicesRef.doc(String(preferredDeviceId).trim()).get();
    if (preferredSnap.exists) {
      const data = preferredSnap.data() || {};
      return {
        deviceId: preferredSnap.id,
        publicKeyHex: normalizePublicKeyHex(data.publicKeyHex),
        platform: String(data.platform || 'android')
      };
    }
  }

  const snapshot = await devicesRef.get();
  for (const doc of snapshot.docs) {
    const data = doc.data() || {};
    try {
      return {
        deviceId: doc.id,
        publicKeyHex: normalizePublicKeyHex(data.publicKeyHex),
        platform: String(data.platform || 'android')
      };
    } catch {
      // Continue until a valid device key is found.
    }
  }

  // Backward compatibility: allow existing users that only published a root-level key.
  const userSnap = await userRef.get();
  const userData = userSnap.data() || {};
  if (userData.digitalKeyPublicHex) {
    return {
      deviceId: String(userData.defaultDeviceId || `legacy-${targetUid}`),
      publicKeyHex: normalizePublicKeyHex(userData.digitalKeyPublicHex),
      platform: 'android'
    };
  }

  throw new HttpsError('failed-precondition', 'Target user has no valid digital key on any device');
}

async function ensureRequesterCanManageVehicle(vehicleId, requesterUid) {
  if (!requesterUid) {
    throw new HttpsError('unauthenticated', 'Authentication is required');
  }

  const vehicle = await getVehicleContext(vehicleId);
  if (vehicle.ownerUid && vehicle.ownerUid === requesterUid) {
    return { vehicle, access: 'owner', delegateShare: null };
  }

  const sharesSnapshot = await db.collection('shares')
    .where('vehicleId', '==', String(vehicleId))
    .where('granteeUid', '==', String(requesterUid))
    .where('status', '==', 'active')
    .limit(20)
    .get();

  const now = Date.now();
  const delegateShare = sharesSnapshot.docs.find((doc) => {
    const data = doc.data() || {};
    const expiresAt = Number(data.expiresAt || 0);
    const permissionMask = Number(data.permissions ?? data.permissionMask ?? 0);
    const role = String(data.role || '').trim().toUpperCase();
    const notExpired = expiresAt <= 0 || expiresAt > now;
    return notExpired && (permissionMask === 0xFF || role === 'FULL');
  }) || null;

  if (!delegateShare) {
    throw new HttpsError('permission-denied', 'Only the owner or a full-access delegate can manage credentials');
  }

  return { vehicle, access: 'delegate-admin', delegateShare };
}

async function enqueueVehicleSync(vehicleId, payload) {
  return db.runTransaction(async (tx) => {
    const vehicleRef = db.collection('vehicles').doc(vehicleId);
    const vehicleSnap = await tx.get(vehicleRef);
    if (!vehicleSnap.exists) {
      throw new Error(`Vehicle ${vehicleId} not found for sync queue`);
    }

    const currentVersion = Number(vehicleSnap.get('syncVersion') || 0);
    const nextVersion = currentVersion + 1;
    const syncRef = vehicleRef.collection('syncQueue').doc();

    tx.set(vehicleRef, {
      syncVersion: nextVersion,
      'tb.lastPendingSyncVersion': nextVersion,
      'tb.lastSyncRequestedAt': SERVER_TIMESTAMP
    }, { merge: true });

    tx.set(syncRef, {
      ...payload,
      syncVersion: nextVersion,
      acked: false,
      createdAt: SERVER_TIMESTAMP
    });

    return nextVersion;
  });
}

function serializeTimestamp(value) {
  if (!value) return null;
  if (typeof value.toMillis === 'function') return value.toMillis();
  if (typeof value === 'number') return value;
  return null;
}

function validateVehicleSyncRequest(vehicle, providedToken) {
  const expected = String(vehicle.tb.syncToken || vehicle.tb.deviceId || '').trim();
  const received = String(providedToken || '').trim();
  return expected.length > 0 && received === expected;
}

function normalizeDevicePublicKeyHex(value) {
  const normalized = String(value || '').trim().toUpperCase();
  if (!normalized) return '';
  if (normalized.length !== 130 || !normalized.startsWith('04') || /[^0-9A-F]/.test(normalized)) {
    throw new HttpsError('invalid-argument', 'devicePublicKeyHex must be an uncompressed P-256 public key');
  }
  return normalized;
}

async function persistVehicleDevicePublicKey(vehicleRef, currentTb = {}, devicePublicKeyHex = '') {
  const normalized = normalizeDevicePublicKeyHex(devicePublicKeyHex);
  if (!normalized || String(currentTb.devicePublicKeyHex || '').trim().toUpperCase() === normalized) {
    return normalized;
  }

  await vehicleRef.set({
    tb: {
      ...currentTb,
      devicePublicKeyHex: normalized,
      devicePublicKeyUpdatedAt: SERVER_TIMESTAMP
    }
  }, { merge: true });

  return normalized;
}

function packCredential(doc) {
  const data = doc.data() || {};
  const publicKeyHex = String(data.publicKeyHex || '').trim().toUpperCase();
  const permissionMask = Number(data.permissionMask || 0x03);
  const expiresAt = Number(data.expiresAt || 0);
  const signingFields = {
    credentialId: doc.id,
    subjectUid: String(data.subjectUid || ''),
    subjectDeviceId: String(data.subjectDeviceId || ''),
    publicKeyHex,
    permissionMask,
    expiresAt
  };

  return {
    credentialId: doc.id,
    subjectUid: signingFields.subjectUid,
    subjectDeviceId: signingFields.subjectDeviceId,
    publicKeyHex,
    role: String(data.role || 'VALET'),
    permissionMask,
    permissions: data.permissions || {},
    expiresAt,
    status: String(data.status || 'active'),
    certRef: data.certRef || `issuer:${ISSUER_ID}`,
    issuerId: String(data.issuerId || ISSUER_ID),
    signatureAlgorithm: String(data.signatureAlgorithm || ISSUER_SIGNATURE_ALGORITHM),
    credentialPackageVersion: Number(data.credentialPackageVersion || CREDENTIAL_PACKAGE_VERSION),
    credentialSignatureDerHex: signCredentialPayload(signingFields),
    issuedAt: serializeTimestamp(data.issuedAt),
    revokedAt: serializeTimestamp(data.revokedAt)
  };
}

export const issueVehicleCredential = onCall(async (request) => {
  const requesterUid = request.auth?.uid;
  if (!requesterUid) {
    throw new HttpsError('unauthenticated', 'Authentication is required');
  }

  const {
    vehicleId,
    targetUid = '',
    targetEmail = '',
    targetDeviceId = '',
    role = 'VALET',
    expiresAt = 0,
    permissions = null,
    ownerUsername = ''
  } = request.data || {};

  const { vehicle, access, delegateShare } = await ensureRequesterCanManageVehicle(vehicleId, requesterUid);

  const targetUser = await resolveTargetUser(targetUid, targetEmail);
  if (targetUser.uid === vehicle.ownerUid) {
    throw new HttpsError('invalid-argument', 'Cannot issue a delegated key to the owner account');
  }

  const targetDevice = await resolveTargetDevice(targetUser.uid, targetDeviceId);
  const roleConfig = permissions != null
    ? roleConfigFromMask(permissions)
    : ROLE_CONFIG[normalizeRole(role)] || ROLE_CONFIG.VALET;

  const safeExpiresAt = Number(expiresAt || 0);
  if (safeExpiresAt < 0 || (safeExpiresAt > 0 && safeExpiresAt <= Date.now())) {
    throw new HttpsError('invalid-argument', 'expiresAt must be 0 or a future unix timestamp in milliseconds');
  }

  if (access === 'delegate-admin' && delegateShare) {
    const delegateData = delegateShare.data() || {};
    const delegateExpiresAt = Number(delegateData.expiresAt || 0);
    if (delegateExpiresAt > 0 && (safeExpiresAt <= 0 || safeExpiresAt > delegateExpiresAt)) {
      throw new HttpsError('failed-precondition', 'Delegated full-access users cannot issue credentials beyond their own expiration');
    }
  }

  const shareRef = db.collection('shares').doc();
  const credentialRef = vehicle.ref.collection('credentials').doc(shareRef.id);
  const shareRequestRef = db.collection('shareRequests').doc(shareRef.id);
  const lookupRef = db.collection('shareLookup')
    .doc(String(vehicleId))
    .collection('recipients')
    .doc(targetUser.uid);

  const vehicleSummary = getVehicleSummary(vehicle.data);
  const shareDoc = {
    vehicleId: String(vehicleId),
    credentialId: credentialRef.id,
    vehicleName: vehicleSummary.vehicleName,
    vehiclePlate: vehicleSummary.vehiclePlate,
    tbDeviceId: vehicleSummary.tbDeviceId,
    ecuVehicleId: vehicleSummary.ecuVehicleId,
    vehicleLocation: vehicleSummary.vehicleLocation,
    vehicleActive: vehicleSummary.vehicleActive,
    vehicleLatitude: vehicleSummary.vehicleLatitude,
    vehicleLongitude: vehicleSummary.vehicleLongitude,
    ownerUid: vehicle.ownerUid,
    ownerUsername: String(ownerUsername || request.auth?.token?.name || request.auth?.token?.email || ''),
    issuedByUid: requesterUid,
    granteeUid: targetUser.uid,
    granteeEmail: String(targetUser.email || targetEmail || ''),
    role: roleConfig.role,
    permissions: roleConfig.permissionMask,
    expiresAt: safeExpiresAt,
    subjectDeviceId: targetDevice.deviceId,
    publicKeyHex: targetDevice.publicKeyHex,
    status: 'active',
    issuedVia: 'firebase-functions',
    createdAt: SERVER_TIMESTAMP,
    updatedAt: SERVER_TIMESTAMP
  };

  const credentialDoc = {
    credentialId: credentialRef.id,
    subjectUid: targetUser.uid,
    subjectDeviceId: targetDevice.deviceId,
    publicKeyHex: targetDevice.publicKeyHex,
    role: roleConfig.role,
    permissionMask: roleConfig.permissionMask,
    permissions: roleConfig.permissions,
    expiresAt: safeExpiresAt,
    status: 'active',
    certRef: `issuer:${ISSUER_ID}`,
    issuer: 'firebase-functions',
    issuerId: ISSUER_ID,
    signatureAlgorithm: ISSUER_SIGNATURE_ALGORITHM,
    credentialPackageVersion: CREDENTIAL_PACKAGE_VERSION,
    issuedAt: SERVER_TIMESTAMP,
    issuedByUid: requesterUid,
    updatedAt: SERVER_TIMESTAMP
  };

  const batch = db.batch();
  batch.set(shareRef, shareDoc);
  batch.set(credentialRef, credentialDoc);
  batch.set(shareRequestRef, {
    requestId: shareRef.id,
    vehicleId: String(vehicleId),
    ownerUid: vehicle.ownerUid,
    requestedByUid: requesterUid,
    targetUid: targetUser.uid,
    targetDeviceId: targetDevice.deviceId,
    role: roleConfig.role,
    permissions: roleConfig.permissionMask,
    expiresAt: safeExpiresAt,
    state: 'issued',
    createdAt: SERVER_TIMESTAMP
  });
  batch.set(lookupRef, {
    vehicleId: String(vehicleId),
    credentialId: credentialRef.id,
    ownerUid: vehicle.ownerUid,
    granteeUid: targetUser.uid,
    role: roleConfig.role,
    permissions: roleConfig.permissionMask,
    expiresAt: safeExpiresAt,
    status: 'active',
    updatedAt: SERVER_TIMESTAMP
  });
  await batch.commit();

  const syncVersion = await enqueueVehicleSync(String(vehicleId), {
    op: 'UPSERT_CREDENTIAL',
    vehicleId: String(vehicleId),
    shareId: shareRef.id,
    credentialId: credentialRef.id,
    targetUid: targetUser.uid,
    reason: 'issue'
  });

  return {
    ok: true,
    shareId: shareRef.id,
    credentialId: credentialRef.id,
    role: roleConfig.role,
    permissionMask: roleConfig.permissionMask,
    expiresAt: safeExpiresAt,
    syncVersion
  };
});

export const revokeVehicleCredential = onCall(async (request) => {
  const requesterUid = request.auth?.uid;
  if (!requesterUid) {
    throw new HttpsError('unauthenticated', 'Authentication is required');
  }

  const { vehicleId, shareId = '', credentialId = '', targetUid = '', reason = 'owner_revoked' } = request.data || {};
  const { vehicle } = await ensureRequesterCanManageVehicle(vehicleId, requesterUid);

  let shareDocs = [];
  if (shareId) {
    const snap = await db.collection('shares').doc(String(shareId)).get();
    if (snap.exists) shareDocs = [snap];
  } else if (credentialId) {
    const query = await db.collection('shares')
      .where('vehicleId', '==', String(vehicleId))
      .where('credentialId', '==', String(credentialId))
      .limit(10)
      .get();
    shareDocs = query.docs;
  } else if (targetUid) {
    const query = await db.collection('shares')
      .where('vehicleId', '==', String(vehicleId))
      .where('granteeUid', '==', String(targetUid))
      .where('status', '==', 'active')
      .limit(20)
      .get();
    shareDocs = query.docs;
  } else {
    throw new HttpsError('invalid-argument', 'shareId, credentialId or targetUid is required');
  }

  if (!shareDocs.length) {
    throw new HttpsError('not-found', 'No active share found for revoke request');
  }

  const batch = db.batch();
  const syncPayloads = [];

  for (const shareDoc of shareDocs) {
    const data = shareDoc.data() || {};
    if (String(data.vehicleId || '') !== String(vehicleId)) continue;

    batch.set(shareDoc.ref, {
      status: 'revoked',
      revokedAt: SERVER_TIMESTAMP,
      revokeReason: String(reason || 'owner_revoked')
    }, { merge: true });

    if (data.credentialId) {
      const credentialRef = vehicle.ref.collection('credentials').doc(String(data.credentialId));
      batch.set(credentialRef, {
        status: 'revoked',
        revokedAt: SERVER_TIMESTAMP,
        revokeReason: String(reason || 'owner_revoked')
      }, { merge: true });

      syncPayloads.push({
        op: 'REVOKE_CREDENTIAL',
        vehicleId: String(vehicleId),
        shareId: shareDoc.id,
        credentialId: String(data.credentialId),
        targetUid: String(data.granteeUid || ''),
        reason: String(reason || 'owner_revoked')
      });
    }

    const lookupRef = db.collection('shareLookup')
      .doc(String(vehicleId))
      .collection('recipients')
      .doc(String(data.granteeUid || targetUid || ''));
    batch.delete(lookupRef);
  }

  await batch.commit();

  let latestSyncVersion = Number(vehicle.data.syncVersion || 0);
  for (const payload of syncPayloads) {
    latestSyncVersion = await enqueueVehicleSync(String(vehicleId), payload);
  }

  return {
    ok: true,
    revokedCount: syncPayloads.length,
    syncVersion: latestSyncVersion
  };
});

export const getVehicleSyncDelta = onRequest(async (req, res) => {
  try {
    const vehicleId = String(req.query.vehicleId || req.body?.vehicleId || '').trim();
    const ecuVehicleId = String(req.query.ecuVehicleId || req.body?.ecuVehicleId || '').trim().toUpperCase();
    const sinceVersion = Number(req.query.sinceVersion || req.body?.sinceVersion || 0);
    const deviceId = String(
      req.get('x-smartkey-device-id') || req.query.deviceId || req.body?.deviceId || ''
    ).trim();
    const syncToken = String(
      req.get('x-smartkey-sync-token') || req.query.syncToken || req.body?.syncToken || ''
    ).trim();
    const devicePublicKeyHex = String(
      req.get('x-smartkey-device-pub') || req.query.devicePublicKeyHex || req.body?.devicePublicKeyHex || ''
    ).trim();

    const vehicle = await getVehicleContext(vehicleId, ecuVehicleId, deviceId);
    if (!deviceId || String(vehicle.tb.deviceId || '').trim() !== deviceId || !validateVehicleSyncRequest(vehicle, syncToken)) {
      res.status(403).json({ ok: false, error: 'vehicle device authentication failed' });
      return;
    }

    const normalizedDevicePub = await persistVehicleDevicePublicKey(vehicle.ref, vehicle.tb, devicePublicKeyHex);
    const latestVersion = Number(vehicle.data.syncVersion || 0);
    if (sinceVersion <= 0) {
      const activeSnapshot = await vehicle.ref.collection('credentials')
        .where('status', '==', 'active')
        .get();

      res.json({
        ok: true,
        vehicleId: vehicle.snap.id,
        mode: 'full',
        fromVersion: 0,
        latestVersion,
        devicePublicKeyHex: normalizedDevicePub || String(vehicle.tb.devicePublicKeyHex || '').trim().toUpperCase(),
        activeCredentials: activeSnapshot.docs.map(packCredential),
        events: []
      });
      return;
    }

    const queueSnapshot = await vehicle.ref.collection('syncQueue')
      .where('syncVersion', '>', sinceVersion)
      .orderBy('syncVersion', 'asc')
      .limit(MAX_SYNC_EVENTS)
      .get();

    const events = [];
    const upserts = [];
    const revocations = [];

    for (const doc of queueSnapshot.docs) {
      const data = doc.data() || {};
      events.push({
        eventId: doc.id,
        op: String(data.op || 'UNKNOWN'),
        credentialId: String(data.credentialId || ''),
        shareId: String(data.shareId || ''),
        syncVersion: Number(data.syncVersion || 0),
        createdAt: serializeTimestamp(data.createdAt)
      });

      if (data.op === 'UPSERT_CREDENTIAL' && data.credentialId) {
        const credentialSnap = await vehicle.ref.collection('credentials').doc(String(data.credentialId)).get();
        if (credentialSnap.exists) {
          upserts.push(packCredential(credentialSnap));
        }
      } else if (data.credentialId) {
        revocations.push({
          credentialId: String(data.credentialId),
          syncVersion: Number(data.syncVersion || 0),
          reason: String(data.reason || 'revoked')
        });
      }
    }

    res.json({
      ok: true,
      vehicleId: vehicle.snap.id,
      mode: 'delta',
      fromVersion: sinceVersion,
      latestVersion,
      devicePublicKeyHex: normalizedDevicePub || String(vehicle.tb.devicePublicKeyHex || '').trim().toUpperCase(),
      events,
      upserts,
      revocations
    });
  } catch (error) {
    logger.error('getVehicleSyncDelta failed', error);
    res.status(500).json({ ok: false, error: error.message || 'internal error' });
  }
});

export const ackVehicleSync = onRequest(async (req, res) => {
  try {
    const vehicleId = String(req.query.vehicleId || req.body?.vehicleId || '').trim();
    const ecuVehicleId = String(req.query.ecuVehicleId || req.body?.ecuVehicleId || '').trim().toUpperCase();
    const ackVersion = Number(req.query.ackVersion || req.body?.ackVersion || 0);
    const deviceId = String(
      req.get('x-smartkey-device-id') || req.query.deviceId || req.body?.deviceId || ''
    ).trim();
    const syncToken = String(
      req.get('x-smartkey-sync-token') || req.query.syncToken || req.body?.syncToken || ''
    ).trim();
    const devicePublicKeyHex = String(
      req.get('x-smartkey-device-pub') || req.query.devicePublicKeyHex || req.body?.devicePublicKeyHex || ''
    ).trim();

    if (!ackVersion) {
      res.status(400).json({ ok: false, error: 'ackVersion is required' });
      return;
    }

    const vehicle = await getVehicleContext(vehicleId, ecuVehicleId, deviceId);
    if (!deviceId || String(vehicle.tb.deviceId || '').trim() !== deviceId || !validateVehicleSyncRequest(vehicle, syncToken)) {
      res.status(403).json({ ok: false, error: 'vehicle device authentication failed' });
      return;
    }

    await persistVehicleDevicePublicKey(vehicle.ref, vehicle.tb, devicePublicKeyHex);

    const queueSnapshot = await vehicle.ref.collection('syncQueue')
      .where('syncVersion', '<=', ackVersion)
      .orderBy('syncVersion', 'asc')
      .limit(MAX_SYNC_EVENTS)
      .get();

    const batch = db.batch();
    queueSnapshot.docs.forEach((doc) => {
      batch.set(doc.ref, { acked: true, ackedAt: SERVER_TIMESTAMP }, { merge: true });
    });
    batch.set(vehicle.ref, {
      'tb.lastAckedSyncVersion': ackVersion,
      'tb.lastSyncedAt': SERVER_TIMESTAMP
    }, { merge: true });
    await batch.commit();

    res.json({ ok: true, vehicleId: vehicle.snap.id, ackedCount: queueSnapshot.size, ackVersion });
  } catch (error) {
    logger.error('ackVehicleSync failed', error);
    res.status(500).json({ ok: false, error: error.message || 'internal error' });
  }
});

export const notifyShareCreated = onDocumentCreated('shares/{shareId}', async (event) => {
  const data = event.data?.data();
  if (!data) return;

  const tokens = await fetchTokensForUser(data.granteeUid);
  if (!tokens.length) {
    logger.info('No tokens for grantee', data.granteeUid);
    return;
  }

  const roleLabel = data.role === 'FULL' ? 'toàn quyền' : (data.role || 'chia sẻ');
  const message = {
    notification: {
      title: 'Bạn vừa nhận được SmartKey',
      body: `${data.ownerUsername || 'Người dùng'} đã cấp quyền ${roleLabel} cho xe ${data.vehicleName}`
    },
    tokens
  };
  await admin.messaging().sendEachForMulticast(message);
});

export const remindShareExpiring = onSchedule('every 60 minutes', async () => {
  const threshold = Date.now() + 2 * 60 * 60 * 1000; // 2 giờ tới
  const query = await db.collection('shares')
    .where('status', '==', 'active')
    .where('expiresAt', '>', 0)
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

    await doc.ref.update({ reminderSentAt: SERVER_TIMESTAMP });
  }
});

export const cleanupExpiredShares = onSchedule('every 24 hours', async () => {
  const now = Date.now();
  const query = await db.collection('shares')
    .where('status', '==', 'active')
    .where('expiresAt', '>', 0)
    .where('expiresAt', '<=', now)
    .get();

  if (query.empty) return;

  for (const doc of query.docs) {
    const data = doc.data() || {};
    const vehicleId = String(data.vehicleId || '');
    const credentialId = String(data.credentialId || '');
    if (!vehicleId || !credentialId) continue;

    const vehicleRef = db.collection('vehicles').doc(vehicleId);
    const credentialRef = vehicleRef.collection('credentials').doc(credentialId);
    const lookupRef = db.collection('shareLookup')
      .doc(vehicleId)
      .collection('recipients')
      .doc(String(data.granteeUid || ''));

    const batch = db.batch();
    batch.set(doc.ref, {
      status: 'revoked',
      cleanedAt: SERVER_TIMESTAMP,
      revokedAt: SERVER_TIMESTAMP,
      revokeReason: 'expired'
    }, { merge: true });
    batch.set(credentialRef, {
      status: 'revoked',
      revokedAt: SERVER_TIMESTAMP,
      revokeReason: 'expired'
    }, { merge: true });
    batch.delete(lookupRef);
    await batch.commit();

    await enqueueVehicleSync(vehicleId, {
      op: 'REVOKE_CREDENTIAL',
      vehicleId,
      shareId: doc.id,
      credentialId,
      targetUid: String(data.granteeUid || ''),
      reason: 'expired'
    });
  }
});
