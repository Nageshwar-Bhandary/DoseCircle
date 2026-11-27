/**
 * DoseCircle Notification Dispatcher Worker
 *
 * Receives notification requests from authenticated users, verifies Firebase ID tokens,
 * validates dose ownership and status, and dispatches FCM v1 notifications to active caregivers
 * with atomic per-caregiver delivery claims and idempotent duplicate suppression.
 */

const GOOGLE_CERTS_URL =
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
const DELIVERY_LOCK_LEASE_MS = 60000; // 60 seconds lease for in-flight requests

// In-memory certificate cache for warm worker isolates
let certsCache = {
  keys: {}, // kid -> CryptoKey
  expiresAt: 0, // Unix timestamp in ms
};
let lastFetchAttempt = 0;

// In-memory cache for Google OAuth 2.0 access token
let googleOAuthCache = {
  accessToken: null,
  expiresAt: 0,
};

/**
 * Returns a standard HTTP 401 response with a short JSON error.
 * Does not expose sensitive verification details or token contents.
 */
function unauthorizedResponse(message = "Unauthorized") {
  return new Response(JSON.stringify({ error: message }), {
    status: 401,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}

/**
 * Decodes a base64url-encoded string into a Uint8Array.
 */
function base64UrlDecode(str) {
  if (typeof str !== "string" || str.length === 0) {
    throw new Error("Invalid base64url string");
  }
  let base64 = str.replace(/-/g, "+").replace(/_/g, "/");
  while (base64.length % 4 !== 0) {
    base64 += "=";
  }
  const binary = atob(base64);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/**
 * Encodes a Uint8Array into a base64url string.
 */
function base64UrlEncode(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Decodes a base64url-encoded string into UTF-8 text.
 */
function base64UrlDecodeText(str) {
  const bytes = base64UrlDecode(str);
  return new TextDecoder().decode(bytes);
}

/**
 * Reads an ASN.1 length header starting at `offset`.
 */
function readLength(bytes, offset) {
  if (offset >= bytes.length) {
    throw new Error("Truncated ASN.1 buffer");
  }
  const initial = bytes[offset];
  if ((initial & 0x80) === 0) {
    return { length: initial, headerSize: 1 };
  }
  const numOctets = initial & 0x7f;
  if (numOctets === 0 || numOctets > 4 || offset + 1 + numOctets > bytes.length) {
    throw new Error("Invalid ASN.1 length");
  }
  let len = 0;
  for (let i = 0; i < numOctets; i++) {
    len = (len << 8) | bytes[offset + 1 + i];
  }
  return { length: len, headerSize: 1 + numOctets };
}

/**
 * Returns metadata and boundary offsets for an ASN.1 DER TLV element.
 */
function getElementBounds(bytes, offset) {
  if (offset >= bytes.length) {
    throw new Error("Offset out of bounds");
  }
  const tag = bytes[offset];
  const headerOffset = offset + 1;
  const { length, headerSize } = readLength(bytes, headerOffset);
  const totalLength = 1 + headerSize + length;
  const endOffset = offset + totalLength;
  if (endOffset > bytes.length) {
    throw new Error("ASN.1 element exceeds buffer bounds");
  }
  return {
    tag,
    totalLength,
    endOffset,
    contentOffset: headerOffset + headerSize,
    length,
  };
}

/**
 * Extracts the SubjectPublicKeyInfo (SPKI) DER sequence from an X.509 certificate DER.
 */
function extractSpkiFromCertDer(certDer) {
  const certElem = getElementBounds(certDer, 0);
  if (certElem.tag !== 0x30) throw new Error("Expected outer Certificate SEQUENCE");

  const tbsElem = getElementBounds(certDer, certElem.contentOffset);
  if (tbsElem.tag !== 0x30) throw new Error("Expected TBSCertificate SEQUENCE");

  let pos = tbsElem.contentOffset;

  if (certDer[pos] === 0xa0) {
    pos += getElementBounds(certDer, pos).totalLength;
  }

  if (certDer[pos] !== 0x02) throw new Error("Expected serialNumber INTEGER");
  pos += getElementBounds(certDer, pos).totalLength;

  if (certDer[pos] !== 0x30) throw new Error("Expected signature AlgorithmIdentifier");
  pos += getElementBounds(certDer, pos).totalLength;

  if (certDer[pos] !== 0x30) throw new Error("Expected issuer Name");
  pos += getElementBounds(certDer, pos).totalLength;

  if (certDer[pos] !== 0x30) throw new Error("Expected validity Validity");
  pos += getElementBounds(certDer, pos).totalLength;

  if (certDer[pos] !== 0x30) throw new Error("Expected subject Name");
  pos += getElementBounds(certDer, pos).totalLength;

  const spkiElem = getElementBounds(certDer, pos);
  if (spkiElem.tag !== 0x30) throw new Error("Expected subjectPublicKeyInfo SEQUENCE");

  return certDer.slice(pos, spkiElem.endOffset);
}

/**
 * Fetches Google's public signing certificates, parses Cache-Control: max-age,
 * extracts public keys, and imports them into WebCrypto.
 */
async function refreshGoogleCertificates() {
  lastFetchAttempt = Date.now();
  const response = await fetch(GOOGLE_CERTS_URL);
  if (!response.ok) {
    throw new Error(`Failed to fetch Google certificates: ${response.status}`);
  }

  const cacheControl = response.headers.get("cache-control") || "";
  const maxAgeMatch = cacheControl.match(/\bmax-age=(\d+)\b/i);
  let maxAgeSeconds = 0;
  if (maxAgeMatch) {
    const parsed = parseInt(maxAgeMatch[1], 10);
    if (!Number.isNaN(parsed) && parsed > 0) {
      maxAgeSeconds = parsed;
    }
  }

  const certsJson = await response.json();
  const newKeys = {};

  for (const [kid, pem] of Object.entries(certsJson)) {
    try {
      const b64 = pem.replace(/-----[^\n]+-----|\r|\n/g, "");
      const der = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const spki = extractSpkiFromCertDer(der);
      const cryptoKey = await crypto.subtle.importKey(
        "spki",
        spki,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"]
      );
      newKeys[kid] = cryptoKey;
    } catch {
      // Safely ignore individual certificate parse error
    }
  }

  if (Object.keys(newKeys).length === 0) {
    throw new Error("No valid certificates could be parsed");
  }

  if (maxAgeSeconds > 0) {
    certsCache = {
      keys: newKeys,
      expiresAt: Date.now() + maxAgeSeconds * 1000,
    };
  } else {
    certsCache = {
      keys: {},
      expiresAt: 0,
    };
  }

  return newKeys;
}

/**
 * Retrieves the WebCrypto CryptoKey for a given key ID, refreshing certificates if needed.
 */
async function getPublicKeyForKid(kid) {
  const now = Date.now();
  const isExpired = now >= certsCache.expiresAt;

  if (isExpired) {
    certsCache = { keys: {}, expiresAt: 0 };
    const freshKeys = await refreshGoogleCertificates();
    return freshKeys[kid] || null;
  }

  if (certsCache.keys[kid]) {
    return certsCache.keys[kid];
  }

  try {
    const freshKeys = await refreshGoogleCertificates();
    return freshKeys[kid] || null;
  } catch {
    return null;
  }
}

/**
 * Verifies a Firebase ID token using WebCrypto only.
 */
async function verifyFirebaseIdToken(token, projectId) {
  if (typeof token !== "string") {
    throw new Error("Token must be a string");
  }

  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("Invalid JWT format");
  }

  const [headerB64, payloadB64, signatureB64] = parts;
  if (!headerB64 || !payloadB64 || !signatureB64) {
    throw new Error("Malformed JWT components");
  }

  let header;
  let payload;
  try {
    header = JSON.parse(base64UrlDecodeText(headerB64));
    payload = JSON.parse(base64UrlDecodeText(payloadB64));
  } catch {
    throw new Error("Failed to parse JWT header or payload");
  }

  if (!header || header.alg !== "RS256") {
    throw new Error("Invalid algorithm: must be RS256");
  }
  if (typeof header.kid !== "string" || header.kid.trim().length === 0) {
    throw new Error("Missing or invalid kid in header");
  }

  const expectedIss = `https://securetoken.google.com/${projectId}`;
  if (payload.iss !== expectedIss) {
    throw new Error("Invalid issuer");
  }
  if (payload.aud !== projectId) {
    throw new Error("Invalid audience");
  }
  if (typeof payload.sub !== "string" || payload.sub.trim().length === 0) {
    throw new Error("Invalid subject");
  }

  const currentTimeInSeconds = Math.floor(Date.now() / 1000);
  if (typeof payload.exp !== "number" || payload.exp <= currentTimeInSeconds) {
    throw new Error("Token expired");
  }

  const publicKey = await getPublicKeyForKid(header.kid);
  if (!publicKey) {
    throw new Error("Unknown key ID");
  }

  const signedData = new TextEncoder().encode(`${headerB64}.${payloadB64}`);
  let signatureBytes;
  try {
    signatureBytes = base64UrlDecode(signatureB64);
  } catch {
    throw new Error("Failed to decode token signature");
  }

  const isSignatureValid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    signatureBytes,
    signedData
  );

  if (!isSignatureValid) {
    throw new Error("Signature verification failed");
  }

  return payload.sub;
}

/**
 * Imports a PKCS#8 PEM private key into WebCrypto.
 */
function pemToPkcs8Der(pem) {
  const b64 = pem.replace(/-----[^\n]+-----|\r|\n/g, "");
  const binary = atob(b64);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/**
 * Obtains a Google OAuth 2.0 access token using Service Account assertion grant.
 */
async function getGoogleAccessToken(serviceAccount) {
  const now = Date.now();
  if (googleOAuthCache.accessToken && now < googleOAuthCache.expiresAt - 60000) {
    return googleOAuthCache.accessToken;
  }

  const { client_email, private_key } = serviceAccount;
  if (!client_email || !private_key) {
    throw new Error("Invalid service account configuration");
  }

  const nowSeconds = Math.floor(now / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: client_email,
    sub: client_email,
    aud: "https://oauth2.googleapis.com/token",
    iat: nowSeconds,
    exp: nowSeconds + 3600,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging",
  };

  const headerB64 = base64UrlEncode(new TextEncoder().encode(JSON.stringify(header)));
  const claimsB64 = base64UrlEncode(new TextEncoder().encode(JSON.stringify(claims)));
  const unsignedData = new TextEncoder().encode(`${headerB64}.${claimsB64}`);

  const pkcs8Der = pemToPkcs8Der(private_key);
  const privateCryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    pkcs8Der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signatureBuffer = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateCryptoKey,
    unsignedData
  );
  const signatureB64 = base64UrlEncode(new Uint8Array(signatureBuffer));
  const assertion = `${headerB64}.${claimsB64}.${signatureB64}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  if (!tokenRes.ok) {
    const errText = await tokenRes.text();
    throw new Error(`Google OAuth token exchange failed (${tokenRes.status}): ${errText}`);
  }

  const tokenJson = await tokenRes.json();
  const accessToken = tokenJson.access_token;
  const expiresIn = tokenJson.expires_in || 3600;

  googleOAuthCache = {
    accessToken,
    expiresAt: Date.now() + expiresIn * 1000,
  };

  return accessToken;
}

/**
 * Fetches a dose event from Firestore and verifies ownership.
 */
async function getDoseEvent(projectId, accessToken, patientUid, doseCloudId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${patientUid}/doseEvents/${doseCloudId}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });

  if (res.status === 404) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`Firestore dose read failed (${res.status})`);
  }

  const doc = await res.json();
  const fields = doc.fields || {};
  return {
    status: fields.status?.stringValue || "",
    medicationName: fields.medicationName?.stringValue || "Medication",
    dosage: fields.dosage?.stringValue || "",
    scheduledTime: fields.scheduledTime?.integerValue || null,
  };
}

/**
 * Fetches patient display name from Firestore.
 */
async function getPatientName(projectId, accessToken, patientUid) {
  try {
    const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${patientUid}`;
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (res.ok) {
      const doc = await res.json();
      return doc.fields?.displayName?.stringValue || "Your patient";
    }
  } catch {
    // Fall back to generic label on failure
  }
  return "Your patient";
}

/**
 * Queries active linked caregivers for a patient from Firestore.
 */
async function getActiveCaregivers(projectId, accessToken, patientUid) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`;
  const query = {
    structuredQuery: {
      from: [{ collectionId: "links" }],
      where: {
        compositeFilter: {
          op: "AND",
          filters: [
            {
              fieldFilter: {
                field: { fieldPath: "patientUid" },
                op: "EQUAL",
                value: { stringValue: patientUid },
              },
            },
            {
              fieldFilter: {
                field: { fieldPath: "status" },
                op: "EQUAL",
                value: { stringValue: "active" },
              },
            },
          ],
        },
      },
    },
  };

  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(query),
  });

  if (!res.ok) {
    throw new Error(`Failed to query active caregivers (${res.status})`);
  }

  const results = await res.json();
  const caregivers = [];

  for (const item of results) {
    if (item.document && item.document.fields) {
      const caregiverUid = item.document.fields.caregiverUid?.stringValue;
      if (caregiverUid && !caregivers.includes(caregiverUid)) {
        caregivers.push(caregiverUid);
      }
    }
  }

  return caregivers;
}

/**
 * Fetches the current FCM device token for a caregiver.
 */
async function getCaregiverFcmToken(projectId, accessToken, caregiverUid) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${caregiverUid}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });

  if (!res.ok) {
    return null;
  }

  const doc = await res.json();
  return doc.fields?.fcmToken?.stringValue || null;
}

/**
 * Claims atomic delivery lock for a specific caregiver using Firestore transaction.
 * Concurrency-safe: concurrent requests cannot both acquire the lock.
 * Prevents concurrent duplicate dispatches and enables safe retries for expired claims (>60s).
 */
async function claimDeliveryLock(projectId, accessToken, patientUid, doseCloudId, caregiverUid, maxRetries = 3) {
  const basePath = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;
  const deliveryId = `${doseCloudId}_${caregiverUid}`;
  const docRelPath = `notifyDeliveries/${deliveryId}`;
  const docFullResourceName = `projects/${projectId}/databases/(default)/documents/${docRelPath}`;

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    const now = Date.now();

    // 1. Begin transaction
    const beginRes = await fetch(`${basePath}:beginTransaction`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ options: { readWrite: {} } }),
    });

    if (!beginRes.ok) {
      const errText = await beginRes.text();
      throw new Error(`Failed to begin Firestore transaction (${beginRes.status}): ${errText}`);
    }

    const { transaction } = await beginRes.json();

    // 2. Read existing delivery document inside transaction
    const getRes = await fetch(`${basePath}/${docRelPath}?transaction=${encodeURIComponent(transaction)}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    let docExists = false;
    let docUpdateTime = null;
    let attemptsCount = 0;

    if (getRes.status === 200) {
      docExists = true;
      const doc = await getRes.json();
      docUpdateTime = doc.updateTime;
      const fields = doc.fields || {};
      const status = fields.status?.stringValue || "";
      const updatedAt = parseInt(fields.updatedAt?.integerValue || "0", 10);
      attemptsCount = parseInt(fields.attempts?.integerValue || "0", 10);

      if (status === "sent") {
        try {
          await fetch(`${basePath}:rollback`, {
            method: "POST",
            headers: {
              Authorization: `Bearer ${accessToken}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ transaction }),
          });
        } catch {
          // Ignore rollback failure
        }
        return { action: "skip", reason: "already_sent" };
      }

      if (status === "sending" && now - updatedAt < DELIVERY_LOCK_LEASE_MS) {
        try {
          await fetch(`${basePath}:rollback`, {
            method: "POST",
            headers: {
              Authorization: `Bearer ${accessToken}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ transaction }),
          });
        } catch {
          // Ignore rollback failure
        }
        return { action: "skip", reason: "in_flight" };
      }
    } else if (getRes.status !== 404) {
      const errText = await getRes.text();
      throw new Error(`Failed to read delivery doc in transaction (${getRes.status}): ${errText}`);
    }

    // 3. Document does not exist OR previous lease expired/failed: attempt atomic commit
    const commitBody = {
      transaction,
      writes: [
        {
          update: {
            name: docFullResourceName,
            fields: {
              status: { stringValue: "sending" },
              updatedAt: { integerValue: `${now}` },
              attempts: { integerValue: `${attemptsCount + 1}` },
            },
          },
          updateMask: {
            fieldPaths: ["status", "updatedAt", "attempts"],
          },
          currentDocument: docExists
            ? { updateTime: docUpdateTime }
            : { exists: false },
        },
      ],
    };

    const commitRes = await fetch(`${basePath}:commit`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(commitBody),
    });

    if (commitRes.ok) {
      return { action: "send" };
    }

    // If 409 Conflict / ABORTED occurred due to concurrent transaction race:
    if (commitRes.status === 409 || commitRes.status === 400) {
      if (attempt < maxRetries - 1) {
        await new Promise((r) => setTimeout(r, 10 + Math.random() * 20));
        continue;
      }
      return { action: "skip", reason: "in_flight" };
    }

    const errText = await commitRes.text();
    throw new Error(`Failed to commit delivery lock transaction (${commitRes.status}): ${errText}`);
  }

  return { action: "skip", reason: "in_flight" };
}

/**
 * Records final delivery state (sent or failed) in Firestore under notifyDeliveries/{deliveryId}.
 */
async function recordDeliveryOutcome(projectId, accessToken, patientUid, doseCloudId, caregiverUid, status, detail = "") {
  const deliveryId = `${doseCloudId}_${caregiverUid}`;
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/notifyDeliveries/${deliveryId}`;
  const now = Date.now();

  const fields = {
    status: { stringValue: status },
    updatedAt: { integerValue: `${now}` },
  };

  if (status === "sent") {
    fields.sentAt = { integerValue: `${now}` };
    if (detail) {
      fields.fcmMessageId = { stringValue: detail };
    }
  } else if (status === "failed") {
    fields.error = { stringValue: detail };
  }

  const fieldPaths = Object.keys(fields).map((k) => `updateMask.fieldPaths=${k}`).join("&");
  await fetch(`${url}?${fieldPaths}`, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields }),
  });
}

/**
 * Sends a data-only FCM push message via HTTP v1 API with Android HIGH priority.
 */
async function sendFcmDataMessage(projectId, accessToken, fcmToken, payloadData) {
  const url = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;
  const body = {
    message: {
      token: fcmToken,
      data: payloadData,
      android: {
        priority: "HIGH",
      },
    },
  };

  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`FCM error (${res.status}): ${errText}`);
  }

  const json = await res.json();
  return json.name || "fcm_message_ok";
}

export default {
  async fetch(request, env, ctx) {
    try {
      const url = new URL(request.url);

      if (request.method !== "POST" || url.pathname !== "/notify") {
        return unauthorizedResponse();
      }

      const authHeader = request.headers.get("Authorization") || "";
      const authMatch = authHeader.match(/^Bearer\s+(.+)$/i);
      if (!authMatch) {
        return unauthorizedResponse();
      }
      const idToken = authMatch[1].trim();
      if (!idToken) {
        return unauthorizedResponse();
      }

      let body;
      try {
        body = await request.json();
      } catch {
        return unauthorizedResponse();
      }

      if (
        !body ||
        typeof body !== "object" ||
        typeof body.doseCloudId !== "string" ||
        body.doseCloudId.trim().length === 0
      ) {
        return unauthorizedResponse();
      }

      const projectId = env?.FIREBASE_PROJECT_ID;
      if (typeof projectId !== "string" || projectId.trim().length === 0) {
        return unauthorizedResponse();
      }

      // 1. Authenticate patient ID token
      const verifiedUid = await verifyFirebaseIdToken(idToken, projectId.trim());
      const patientUid = verifiedUid;
      const doseCloudId = body.doseCloudId.trim();

      // If no service account is configured, return authenticated 200 (for basic auth checks)
      if (!env?.FIREBASE_SERVICE_ACCOUNT) {
        return new Response(
          JSON.stringify({
            ok: true,
            uid: patientUid,
            message: "Authentication verified. Service account credentials not configured on Worker.",
          }),
          {
            status: 200,
            headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
          }
        );
      }

      // 2. Parse Service Account credentials
      let serviceAccount;
      try {
        serviceAccount = typeof env.FIREBASE_SERVICE_ACCOUNT === "string"
          ? JSON.parse(env.FIREBASE_SERVICE_ACCOUNT)
          : env.FIREBASE_SERVICE_ACCOUNT;
      } catch {
        return new Response(JSON.stringify({ error: "Invalid FIREBASE_SERVICE_ACCOUNT format" }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        });
      }

      const googleAccessToken = await getGoogleAccessToken(serviceAccount);

      // 3. Read and verify dose event status
      const doseEvent = await getDoseEvent(projectId.trim(), googleAccessToken, patientUid, doseCloudId);
      if (!doseEvent) {
        return new Response(JSON.stringify({ error: "Dose event not found" }), {
          status: 404,
          headers: { "Content-Type": "application/json" },
        });
      }

      if (doseEvent.status !== "MISSED") {
        return new Response(
          JSON.stringify({ error: `Dose is not MISSED (current: ${doseEvent.status})` }),
          { status: 400, headers: { "Content-Type": "application/json" } }
        );
      }

      // 4. Stale-dose 6-hour cutoff: reject doses scheduled more than 6 hours ago
      const STALE_CUTOFF_MS = 6 * 60 * 60 * 1000; // 6 hours
      const scheduledTime = doseEvent.scheduledTime !== null ? parseInt(doseEvent.scheduledTime, 10) : null;
      if (scheduledTime === null || isNaN(scheduledTime) || scheduledTime <= 0) {
        return new Response(
          JSON.stringify({ error: "Dose has invalid or missing scheduledTime" }),
          { status: 400, headers: { "Content-Type": "application/json" } }
        );
      }
      if (Date.now() - scheduledTime > STALE_CUTOFF_MS) {
        return new Response(
          JSON.stringify({
            ok: true,
            doseCloudId,
            patientUid,
            summary: { totalCaregivers: 0, sent: 0, failed: 0, skipped: "dose_too_old" },
            message: "Dose is too old to notify caregivers (scheduled more than 6 hours ago)",
          }),
          { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }
        );
      }

      // 5. Resolve patient name and active caregivers
      const patientName = await getPatientName(projectId.trim(), googleAccessToken, patientUid);
      const activeCaregivers = await getActiveCaregivers(projectId.trim(), googleAccessToken, patientUid);

      if (activeCaregivers.length === 0) {
        return new Response(
          JSON.stringify({
            ok: true,
            doseCloudId,
            patientUid,
            summary: { totalCaregivers: 0, sent: 0, failed: 0, skipped: 0 },
            message: "No active caregivers linked",
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      }

      // 6. Deliver to each caregiver with atomic locks and duplicate suppression
      const deliveries = [];
      let sentCount = 0;
      let failedCount = 0;
      let skippedCount = 0;

      for (const caregiverUid of activeCaregivers) {
        const stableNotificationId = `${doseCloudId}_${caregiverUid}`;

        try {
          const claim = await claimDeliveryLock(
            projectId.trim(),
            googleAccessToken,
            patientUid,
            doseCloudId,
            caregiverUid
          );

          if (claim.action === "skip") {
            skippedCount++;
            deliveries.push({
              caregiverUid,
              status: "skipped",
              reason: claim.reason,
              stableNotificationId,
            });
            continue;
          }

          const fcmToken = await getCaregiverFcmToken(
            projectId.trim(),
            googleAccessToken,
            caregiverUid
          );

          if (!fcmToken) {
            failedCount++;
            await recordDeliveryOutcome(
              projectId.trim(),
              googleAccessToken,
              patientUid,
              doseCloudId,
              caregiverUid,
              "failed",
              "Missing FCM token"
            );
            deliveries.push({
              caregiverUid,
              status: "failed",
              reason: "Missing FCM token",
              stableNotificationId,
            });
            continue;
          }

          const dosageStr = doseEvent.dosage ? ` (${doseEvent.dosage})` : "";
          const fcmMessageId = await sendFcmDataMessage(
            projectId.trim(),
            googleAccessToken,
            fcmToken,
            {
              title: "Missed Dose Alert",
              body: `${patientName} missed their dose of ${doseEvent.medicationName}${dosageStr}.`,
              doseCloudId,
              patientUid,
              caregiverUid,
              stableNotificationId,
            }
          );

          sentCount++;
          await recordDeliveryOutcome(
            projectId.trim(),
            googleAccessToken,
            patientUid,
            doseCloudId,
            caregiverUid,
            "sent",
            fcmMessageId
          );
          deliveries.push({
            caregiverUid,
            status: "sent",
            stableNotificationId,
            fcmMessageId,
          });
        } catch (err) {
          failedCount++;
          try {
            await recordDeliveryOutcome(
              projectId.trim(),
              googleAccessToken,
              patientUid,
              doseCloudId,
              caregiverUid,
              "failed",
              err.message
            );
          } catch {
            // Ignore secondary failure
          }
          deliveries.push({
            caregiverUid,
            status: "failed",
            reason: err.message,
            stableNotificationId,
          });
        }
      }

      const allFailed = sentCount === 0 && failedCount > 0;
      return new Response(
        JSON.stringify({
          ok: !allFailed,
          doseCloudId,
          patientUid,
          summary: {
            totalCaregivers: activeCaregivers.length,
            sent: sentCount,
            failed: failedCount,
            skipped: skippedCount,
          },
          deliveries,
        }),
        {
          status: allFailed ? 502 : 200,
          headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
        }
      );
    } catch {
      return unauthorizedResponse();
    }
  },
};
