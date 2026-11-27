import worker from "./src/index.js";
import assert from "node:assert";
import nodeCrypto from "node:crypto";

// Helper to base64url encode
function base64UrlEncode(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function strToBytes(str) {
  return new TextEncoder().encode(str);
}

async function exportSpkiDer(key) {
  const spkiBuffer = await crypto.subtle.exportKey("spki", key);
  return new Uint8Array(spkiBuffer);
}

function wrapSpkiInCertDer(spkiDer) {
  function encodeLength(len) {
    if (len < 128) return [len];
    if (len < 256) return [0x81, len];
    return [0x82, (len >> 8) & 0xff, len & 0xff];
  }
  function tag(tagByte, contentBytes) {
    const lenBytes = encodeLength(contentBytes.length);
    return new Uint8Array([tagByte, ...lenBytes, ...contentBytes]);
  }

  const serial = tag(0x02, [0x01]);
  const sigAlg = tag(0x30, [0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00]);
  const issuer = tag(0x30, []);
  const validity = tag(0x30, [
    ...tag(0x17, strToBytes("260101000000Z")),
    ...tag(0x17, strToBytes("300101000000Z"))
  ]);
  const subject = tag(0x30, []);

  const tbsContent = [
    ...serial,
    ...sigAlg,
    ...issuer,
    ...validity,
    ...subject,
    ...spkiDer
  ];
  const tbs = tag(0x30, tbsContent);
  const outerSigAlg = sigAlg;
  const sigVal = tag(0x03, [0x00, 0xaa, 0xbb]);

  return tag(0x30, [...tbs, ...outerSigAlg, ...sigVal]);
}

function derToPem(der) {
  let binary = "";
  for (let i = 0; i < der.length; i++) binary += String.fromCharCode(der[i]);
  const b64 = btoa(binary);
  return `-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----`;
}

async function runTests() {
  console.log("=== Running DoseCircle Worker Automated Test Suite ===");
  const env = { FIREBASE_PROJECT_ID: "dosecircle-6cc09" };

  // 1. Method / Path rejection
  {
    console.log("Test 1: Method rejection (GET /notify)");
    const req = new Request("https://example.com/notify", { method: "GET" });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
    const body = await res.json();
    assert.strictEqual(body.error, "Unauthorized");
  }

  {
    console.log("Test 2: Path rejection (POST /other)");
    const req = new Request("https://example.com/other", { method: "POST" });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  // 2. Authorization header checks
  {
    console.log("Test 3: Missing Authorization header");
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: { "Content-Type": "application/json" }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 4: Non-Bearer Authorization header");
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Basic 12345"
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  // 3. Body validation checks
  {
    console.log("Test 5: Malformed JSON body");
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: "{ not json",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer some-token"
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 6: Missing doseCloudId in body");
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ other: "field" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer some-token"
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 7: Empty doseCloudId string");
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "   " }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer some-token"
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  // 4. Token validation checks
  {
    console.log("Test 8: Malformed JWT token (single segment)");
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer not-a-jwt"
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  // Setup mock keys and certs
  console.log("Generating RSA test keypair for RS256 token verification...");
  const keyPair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"]
  );

  const spkiDer = await exportSpkiDer(keyPair.publicKey);
  const certDer = wrapSpkiInCertDer(spkiDer);
  const certPem = derToPem(certDer);

  const testKid = "test-key-id-123";
  let mockFetchCertsHandler = async () => {
    return new Response(JSON.stringify({ [testKid]: certPem }), {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "public, max-age=3600"
      }
    });
  };

  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    const urlStr = typeof url === "string" ? url : url.url;
    if (urlStr.includes("securetoken@system.gserviceaccount.com")) {
      return mockFetchCertsHandler();
    }
    return realFetch(url, opts);
  };

  async function createTestJwt(headerOverrides = {}, payloadOverrides = {}, sign = true) {
    const header = { alg: "RS256", kid: testKid, ...headerOverrides };
    const now = Math.floor(Date.now() / 1000);
    const payload = {
      iss: "https://securetoken.google.com/dosecircle-6cc09",
      aud: "dosecircle-6cc09",
      sub: "user_test_999",
      exp: now + 3600,
      ...payloadOverrides
    };

    const hB64 = base64UrlEncode(strToBytes(JSON.stringify(header)));
    const pB64 = base64UrlEncode(strToBytes(JSON.stringify(payload)));
    const data = strToBytes(`${hB64}.${pB64}`);

    let sB64;
    if (sign) {
      const sigBuffer = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", keyPair.privateKey, data);
      sB64 = base64UrlEncode(new Uint8Array(sigBuffer));
    } else {
      sB64 = base64UrlEncode(new Uint8Array(256).fill(0x42));
    }

    return `${hB64}.${pB64}.${sB64}`;
  }

  // 5. Successful verification without service account
  {
    console.log("Test 9: Valid RS256 token verification (no service account configured)");
    const token = await createTestJwt();
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.ok, true);
    assert.strictEqual(body.uid, "user_test_999");
  }

  // 6. Security & claim verification tests
  {
    console.log("Test 10: Invalid signature rejection");
    const token = await createTestJwt({}, {}, false);
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 11: Expired token rejection");
    const now = Math.floor(Date.now() / 1000);
    const token = await createTestJwt({}, { exp: now - 100 });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 12: Invalid issuer rejection");
    const token = await createTestJwt({}, { iss: "https://securetoken.google.com/wrong-project" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 13: Invalid audience rejection");
    const token = await createTestJwt({}, { aud: "wrong-project" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 14: Empty sub rejection");
    const token = await createTestJwt({}, { sub: "" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 15: Unknown kid rejection");
    const token = await createTestJwt({ kid: "unknown-key-999" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 16: Non-RS256 algorithm rejection (HS256)");
    const token = await createTestJwt({ alg: "HS256" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, env);
    assert.strictEqual(res.status, 401);
  }

  // 7. Requirement 3 Tests: Require configured Firebase project ID
  {
    console.log("Test 17: Missing env.FIREBASE_PROJECT_ID fails with 401 (no fallback)");
    const token = await createTestJwt();
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, {});
    assert.strictEqual(res.status, 401);
  }

  {
    console.log("Test 18: Empty string env.FIREBASE_PROJECT_ID fails with 401");
    const token = await createTestJwt();
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_123" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, { FIREBASE_PROJECT_ID: "   " });
    assert.strictEqual(res.status, 401);
  }

  // 8. Requirement 2 Tests: Exact Google Cache duration & max-age=0
  {
    console.log("Test 19: Cache-Control max-age=0 is not retained beyond current instant");
    const testKidMaxAge0 = "test-kid-max-age-0";
    let fetchCount = 0;
    mockFetchCertsHandler = async () => {
      fetchCount++;
      return new Response(JSON.stringify({ [testKidMaxAge0]: certPem }), {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=0"
        }
      });
    };

    const token = await createTestJwt({ kid: testKidMaxAge0 });
    const req1 = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_1" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res1 = await worker.fetch(req1, env);
    assert.strictEqual(res1.status, 200);
    assert.strictEqual(fetchCount, 1);

    const req2 = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_2" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res2 = await worker.fetch(req2, env);
    assert.strictEqual(res2.status, 200);
    assert.strictEqual(fetchCount, 2, "Second request with max-age=0 must trigger a fresh fetch");
  }

  {
    console.log("Test 20: Fail closed when certificate cache expires and refresh fails");
    const testKidFailClosed = "test-kid-fail-closed";
    mockFetchCertsHandler = async () => {
      return new Response(JSON.stringify({ [testKidFailClosed]: certPem }), {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=0"
        }
      });
    };

    const token = await createTestJwt({ kid: testKidFailClosed });
    const req1 = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_1" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res1 = await worker.fetch(req1, env);
    assert.strictEqual(res1.status, 200);

    mockFetchCertsHandler = async () => {
      return new Response("Service Unavailable", { status: 503 });
    };

    const req2 = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_2" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res2 = await worker.fetch(req2, env);
    assert.strictEqual(res2.status, 401, "Must return HTTP 401 when cache is expired and refresh fails");
  }

  // =========================================================================
  // End-to-End Caregiver Notification & Concurrency Lock Tests
  // =========================================================================

  console.log("\n=== Setting up Service Account & Firestore Mock Environment ===");

  const { privateKey: saPrivateKeyPem } = nodeCrypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "pem" },
    privateKeyEncoding: { type: "pkcs8", format: "pem" }
  });

  const mockServiceAccount = {
    client_email: "dosecircle-worker@dosecircle-6cc09.iam.gserviceaccount.com",
    private_key: saPrivateKeyPem
  };

  const fullEnv = {
    FIREBASE_PROJECT_ID: "dosecircle-6cc09",
    FIREBASE_SERVICE_ACCOUNT: JSON.stringify(mockServiceAccount)
  };

  // Restore default cert handler for valid tokens
  mockFetchCertsHandler = async () => {
    return new Response(JSON.stringify({ [testKid]: certPem }), {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "public, max-age=3600"
      }
    });
  };

  // In-memory mock database state
  let mockFirestore = {
    doseEvents: {}, // key: "users/{patientUid}/doseEvents/{doseCloudId}"
    users: {},      // key: "users/{uid}"
    links: [],      // array of { patientUid, caregiverUid, status }
    deliveries: {}  // key: "{doseCloudId}_{caregiverUid}" -> { fields, updateTime } (notifyDeliveries collection)
  };

  let sentFcmMessages = [];
  let simulatedConcurrentTxCounter = 0;

  // Intercept Google APIs
  globalThis.fetch = async (url, opts = {}) => {
    const urlStr = typeof url === "string" ? url : url.url;

    // 1. Certificate URL
    if (urlStr.includes("securetoken@system.gserviceaccount.com")) {
      return mockFetchCertsHandler();
    }

    // 2. Google OAuth Token URL
    if (urlStr.includes("oauth2.googleapis.com/token")) {
      return new Response(
        JSON.stringify({
          access_token: "mock-google-access-token-123",
          expires_in: 3600,
          token_type: "Bearer"
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    // 3. Firestore REST API
    if (urlStr.includes("firestore.googleapis.com")) {
      // POST beginTransaction
      if (urlStr.endsWith(":beginTransaction")) {
        return new Response(
          JSON.stringify({ transaction: `tx_${Date.now()}_${Math.random()}` }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      }

      // POST rollback
      if (urlStr.endsWith(":rollback")) {
        return new Response(JSON.stringify({}), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }

      // POST commit
      if (urlStr.endsWith(":commit")) {
        const body = JSON.parse(opts.body);
        const write = body.writes?.[0];
        if (write) {
          const match = write.update.name.match(/notifyDeliveries\/([^/?]+)/);
          if (match) {
            const [, deliveryId] = match;
            const key = deliveryId;
            const existing = mockFirestore.deliveries[key];

            // Verify currentDocument precondition
            if (write.currentDocument) {
              if (write.currentDocument.exists === false && existing) {
                return new Response(
                  JSON.stringify({ error: { code: 409, message: "Document already exists (ABORTED)" } }),
                  { status: 409, headers: { "Content-Type": "application/json" } }
                );
              }
              if (write.currentDocument.updateTime && (!existing || existing.updateTime !== write.currentDocument.updateTime)) {
                return new Response(
                  JSON.stringify({ error: { code: 409, message: "Version conflict (ABORTED)" } }),
                  { status: 409, headers: { "Content-Type": "application/json" } }
                );
              }
            }

            const newUpdateTime = new Date().toISOString();
            mockFirestore.deliveries[key] = {
              fields: {
                ...(existing?.fields || {}),
                ...write.update.fields
              },
              updateTime: newUpdateTime
            };

            return new Response(
              JSON.stringify({ writeResults: [{ updateTime: newUpdateTime }] }),
              { status: 200, headers: { "Content-Type": "application/json" } }
            );
          }
        }
      }

      // POST runQuery on links
      if (urlStr.endsWith(":runQuery")) {
        const query = JSON.parse(opts.body);
        const patientFilter = query.structuredQuery.where.compositeFilter.filters.find(
          f => f.fieldFilter.field.fieldPath === "patientUid"
        )?.fieldFilter.value.stringValue;

        const results = mockFirestore.links
          .filter(l => l.patientUid === patientFilter && l.status === "active")
          .map(l => ({
            document: {
              fields: {
                patientUid: { stringValue: l.patientUid },
                caregiverUid: { stringValue: l.caregiverUid },
                status: { stringValue: l.status }
              }
            }
          }));

        return new Response(JSON.stringify(results), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }

      // notifyDeliveries doc GET or PATCH (new path for delivery records)
      const deliveryMatch = urlStr.match(/notifyDeliveries\/([^/?]+)/);
      if (deliveryMatch) {
        const [, deliveryId] = deliveryMatch;
        const key = deliveryId;

        if (opts.method === "PATCH") {
          const body = JSON.parse(opts.body);
          const existing = mockFirestore.deliveries[key];
          const newUpdateTime = new Date().toISOString();
          mockFirestore.deliveries[key] = {
            fields: {
              ...(existing?.fields || {}),
              ...body.fields
            },
            updateTime: newUpdateTime
          };
          return new Response(JSON.stringify({ fields: mockFirestore.deliveries[key].fields, updateTime: newUpdateTime }), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        } else {
          // GET
          const existing = mockFirestore.deliveries[key];
          if (existing) {
            return new Response(JSON.stringify({ fields: existing.fields, updateTime: existing.updateTime }), {
              status: 200,
              headers: { "Content-Type": "application/json" }
            });
          }
          return new Response("Not found", { status: 404 });
        }
      }

      // DoseEvent doc GET
      const doseEventMatch = urlStr.match(/users\/([^/]+)\/doseEvents\/([^/?]+)$/);
      if (doseEventMatch) {
        const [, pUid, dId] = doseEventMatch;
        const key = `${pUid}/${dId}`;
        const event = mockFirestore.doseEvents[key];
        if (event) {
          const fields = {
            status: { stringValue: event.status },
            medicationName: { stringValue: event.medicationName },
            dosage: { stringValue: event.dosage || "" },
            scheduledTime: { integerValue: `${event.scheduledTime ?? (Date.now() - 60000)}` }
          };
          if (event.scheduledTime === null) {
            delete fields.scheduledTime; // simulate missing field
          }
          return new Response(JSON.stringify({ fields }), { status: 200, headers: { "Content-Type": "application/json" } });
        }
        return new Response("Not found", { status: 404 });
      }

      // User doc GET (for patient name or caregiver token)
      const userMatch = urlStr.match(/users\/([^/?]+)$/);
      if (userMatch) {
        const uid = userMatch[1];
        const user = mockFirestore.users[uid];
        if (user) {
          const fields = {};
          if (user.displayName) fields.displayName = { stringValue: user.displayName };
          if (user.fcmToken) fields.fcmToken = { stringValue: user.fcmToken };
          return new Response(JSON.stringify({ fields }), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        }
        return new Response("User not found", { status: 404 });
      }
    }

    // 4. FCM v1 API
    if (urlStr.includes("fcm.googleapis.com/v1/projects")) {
      const body = JSON.parse(opts.body);
      sentFcmMessages.push(body);
      return new Response(
        JSON.stringify({ name: `projects/dosecircle-6cc09/messages/fcm_msg_${Date.now()}` }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    return realFetch(url, opts);
  };

  // Test 21: Invalid FIREBASE_SERVICE_ACCOUNT JSON format
  {
    console.log("Test 21: Invalid FIREBASE_SERVICE_ACCOUNT returns 500");
    const token = await createTestJwt();
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_100" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, {
      FIREBASE_PROJECT_ID: "dosecircle-6cc09",
      FIREBASE_SERVICE_ACCOUNT: "{ bad json"
    });
    assert.strictEqual(res.status, 500);
    const body = await res.json();
    assert.strictEqual(body.error, "Invalid FIREBASE_SERVICE_ACCOUNT format");
  }

  // Test 22: Dose event not found (404)
  {
    console.log("Test 22: Dose event not found returns 404");
    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "nonexistent_dose" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 404);
    const body = await res.json();
    assert.strictEqual(body.error, "Dose event not found");
  }

  // Test 23: Dose status is TAKEN (not MISSED) -> returns 400
  {
    console.log("Test 23: Dose status not MISSED returns 400");
    mockFirestore.doseEvents["patient_uid_1/dose_taken"] = {
      status: "TAKEN",
      medicationName: "Metformin",
      dosage: "500mg",
      scheduledTime: Date.now() - 60000
    };

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_taken" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 400);
    const body = await res.json();
    assert.strictEqual(body.error, "Dose is not MISSED (current: TAKEN)");
  }

  // Test 24: No active caregivers linked -> returns 200 with zero summary
  {
    console.log("Test 24: No active caregivers returns 200 with totalCaregivers: 0");
    mockFirestore.doseEvents["patient_uid_1/dose_missed_1"] = {
      status: "MISSED",
      medicationName: "Lisinopril",
      dosage: "10mg",
      scheduledTime: Date.now() - 60000 // 1 minute ago, well within 6h cutoff
    };
    mockFirestore.links = []; // no caregivers

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_missed_1" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.ok, true);
    assert.strictEqual(body.summary.totalCaregivers, 0);
  }

  // Test 25: Active caregiver with missing FCM token -> delivery fails, returns 502
  {
    console.log("Test 25: Caregiver without FCM token marks delivery failed");
    mockFirestore.links = [
      { patientUid: "patient_uid_1", caregiverUid: "cg_no_token", status: "active" }
    ];
    mockFirestore.users["cg_no_token"] = { displayName: "Caregiver One" }; // no fcmToken

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_missed_1" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 502); // allFailed = true
    const body = await res.json();
    assert.strictEqual(body.ok, false);
    assert.strictEqual(body.summary.totalCaregivers, 1);
    assert.strictEqual(body.summary.failed, 1);
    assert.strictEqual(body.deliveries[0].status, "failed");
    assert.strictEqual(body.deliveries[0].reason, "Missing FCM token");
    assert.strictEqual(body.deliveries[0].stableNotificationId, "dose_missed_1_cg_no_token");
  }

  // Test 26: Successful end-to-end delivery with multiple caregivers, data-only FCM payload, and Android HIGH priority
  {
    console.log("Test 26: Successful end-to-end dispatch with Android HIGH priority");
    sentFcmMessages = [];
    mockFirestore.deliveries = {}; // reset deliveries
    mockFirestore.users["patient_uid_1"] = { displayName: "John Doe" };
    mockFirestore.users["cg_1"] = { displayName: "Caregiver Alice", fcmToken: "token_alice_123" };
    mockFirestore.users["cg_2"] = { displayName: "Caregiver Bob", fcmToken: "token_bob_456" };
    mockFirestore.links = [
      { patientUid: "patient_uid_1", caregiverUid: "cg_1", status: "active" },
      { patientUid: "patient_uid_1", caregiverUid: "cg_2", status: "active" },
      { patientUid: "patient_uid_1", caregiverUid: "cg_inactive", status: "pending" } // inactive ignored
    ];

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_missed_1" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.ok, true);
    assert.strictEqual(body.summary.totalCaregivers, 2);
    assert.strictEqual(body.summary.sent, 2);
    assert.strictEqual(body.summary.failed, 0);
    assert.strictEqual(body.summary.skipped, 0);

    // Verify 2 FCM messages were sent
    assert.strictEqual(sentFcmMessages.length, 2);
    const msgAlice = sentFcmMessages.find(m => m.message.token === "token_alice_123");
    assert.ok(msgAlice, "FCM message to Alice must exist");
    assert.strictEqual(msgAlice.message.data.stableNotificationId, "dose_missed_1_cg_1");
    assert.strictEqual(msgAlice.message.data.title, "Missed Dose Alert");
    assert.strictEqual(msgAlice.message.data.body, "John Doe missed their dose of Lisinopril (10mg).");
    assert.strictEqual(msgAlice.message.data.doseCloudId, "dose_missed_1");
    assert.strictEqual(msgAlice.message.data.patientUid, "patient_uid_1");
    assert.strictEqual(msgAlice.message.data.caregiverUid, "cg_1");

    // Requirements 3 verification: Android HIGH priority and Data-Only message structure
    assert.strictEqual(msgAlice.message.android?.priority, "HIGH", "Must set Android HIGH priority");
    assert.strictEqual(msgAlice.message.notification, undefined, "Must be data-only FCM payload");

    // Verify Firestore delivery records
    assert.strictEqual(mockFirestore.deliveries["dose_missed_1_cg_1"].fields.status.stringValue, "sent");
    assert.strictEqual(mockFirestore.deliveries["dose_missed_1_cg_2"].fields.status.stringValue, "sent");
  }

  // Test 27: Duplicate dispatch prevention (server-side idempotency)
  {
    console.log("Test 27: Duplicate dispatch skipped with reason 'already_sent'");
    sentFcmMessages = []; // clear
    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_missed_1" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.summary.sent, 0);
    assert.strictEqual(body.summary.skipped, 2);
    assert.strictEqual(body.deliveries[0].reason, "already_sent");
    assert.strictEqual(body.deliveries[1].reason, "already_sent");
    assert.strictEqual(sentFcmMessages.length, 0, "No duplicate FCM messages should be sent");
  }

  // Test 28: In-flight lock deduplication
  {
    console.log("Test 28: In-flight lock (status=sending within lease) skipped with reason 'in_flight'");
    mockFirestore.doseEvents["patient_uid_1/dose_missed_2"] = {
      status: "MISSED",
      medicationName: "Amoxicillin",
      scheduledTime: Date.now() - 60000 // 1 minute ago
    };
    // Simulate an ongoing concurrent delivery
    mockFirestore.deliveries["dose_missed_2_cg_1"] = {
      fields: {
        status: { stringValue: "sending" },
        updatedAt: { integerValue: `${Date.now() - 10000}` }, // 10s ago, well within 60s lease
        attempts: { integerValue: "1" }
      },
      updateTime: new Date(Date.now() - 10000).toISOString()
    };
    mockFirestore.links = [
      { patientUid: "patient_uid_1", caregiverUid: "cg_1", status: "active" }
    ];

    sentFcmMessages = [];
    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_missed_2" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.summary.skipped, 1);
    assert.strictEqual(body.deliveries[0].reason, "in_flight");
    assert.strictEqual(sentFcmMessages.length, 0);
  }

  // Test 29: Expired lease lock reclamation & retry
  {
    console.log("Test 29: Expired lock (>60s) is reclaimed and retried");
    // Simulate crashed worker that left lock in "sending" 70 seconds ago (>60s lease)
    mockFirestore.deliveries["dose_missed_2_cg_1"] = {
      fields: {
        status: { stringValue: "sending" },
        updatedAt: { integerValue: `${Date.now() - 70000}` },
        attempts: { integerValue: "1" }
      },
      updateTime: new Date(Date.now() - 70000).toISOString()
    };

    sentFcmMessages = [];
    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_missed_2" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.summary.sent, 1);
    assert.strictEqual(sentFcmMessages.length, 1);
    assert.strictEqual(mockFirestore.deliveries["dose_missed_2_cg_1"].fields.status.stringValue, "sent");
    assert.strictEqual(mockFirestore.deliveries["dose_missed_2_cg_1"].fields.attempts.integerValue, "2");
  }

  // Test 30: Concurrency simulation with Firestore Transaction conflicts
  {
    console.log("Test 30: Concurrency simulation - concurrent claims ensure exactly one lease is acquired");
    mockFirestore.doseEvents["patient_uid_1/dose_concurrent"] = {
      status: "MISSED",
      medicationName: "Atorvastatin",
      dosage: "20mg",
      scheduledTime: Date.now() - 60000 // 1 minute ago
    };
    mockFirestore.links = [
      { patientUid: "patient_uid_1", caregiverUid: "cg_1", status: "active" }
    ];
    delete mockFirestore.deliveries["dose_concurrent_cg_1"];
    sentFcmMessages = [];

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const reqA = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_concurrent" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const reqB = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_concurrent" }),
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });

    const [resA, resB] = await Promise.all([
      worker.fetch(reqA, fullEnv),
      worker.fetch(reqB, fullEnv)
    ]);

    assert.strictEqual(resA.status, 200);
    assert.strictEqual(resB.status, 200);
    const bodyA = await resA.json();
    const bodyB = await resB.json();

    const totalSent = bodyA.summary.sent + bodyB.summary.sent;
    const totalSkipped = bodyA.summary.skipped + bodyB.summary.skipped;

    assert.strictEqual(totalSent, 1, "Exactly one concurrent request must acquire the lease and send FCM");
    assert.strictEqual(totalSkipped, 1, "The competing concurrent request must skip duplicate dispatch");
    assert.strictEqual(sentFcmMessages.length, 1, "Only one FCM message must ever be dispatched");
  }
  // =========================================================================
  // Stale-Dose 6-Hour Cutoff Tests (31-36)
  // =========================================================================

  // Test 31: scheduledTime within 6h boundary sends notifications
  {
    console.log("Test 31: scheduledTime within 6h boundary sends notifications");
    sentFcmMessages = [];
    mockFirestore.deliveries = {};
    const justWithin6h = Date.now() - (6 * 60 * 60 * 1000 - 60000); // 5h59m ago
    mockFirestore.doseEvents["patient_uid_1/dose_fresh"] = {
      status: "MISSED",
      medicationName: "Aspirin",
      dosage: "81mg",
      scheduledTime: justWithin6h
    };
    mockFirestore.links = [
      { patientUid: "patient_uid_1", caregiverUid: "cg_1", status: "active" }
    ];
    mockFirestore.users["cg_1"] = { displayName: "Caregiver Alice", fcmToken: "token_alice_123" };

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_fresh" }),
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.ok, true);
    assert.strictEqual(body.summary.sent, 1, "Recent dose must be delivered");
    assert.ok(body.summary.skipped !== "dose_too_old", "Recent dose must not be skipped as too old");
  }

  // Test 32: scheduledTime just over 6h returns dose_too_old
  {
    console.log("Test 32: scheduledTime just over 6h returns dose_too_old");
    sentFcmMessages = [];
    const justOver6h = Date.now() - (6 * 60 * 60 * 1000 + 60000); // 6h1m ago
    mockFirestore.doseEvents["patient_uid_1/dose_stale_just"] = {
      status: "MISSED",
      medicationName: "Metoprolol",
      dosage: "25mg",
      scheduledTime: justOver6h
    };

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_stale_just" }),
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.ok, true);
    assert.strictEqual(body.summary.skipped, "dose_too_old");
    assert.strictEqual(body.summary.sent, 0);
    assert.strictEqual(sentFcmMessages.length, 0, "No FCM messages must be sent for stale dose");
  }

  // Test 33: scheduledTime several days ago returns dose_too_old
  {
    console.log("Test 33: scheduledTime several days ago returns dose_too_old");
    const twoDaysAgo = Date.now() - (48 * 60 * 60 * 1000);
    mockFirestore.doseEvents["patient_uid_1/dose_very_old"] = {
      status: "MISSED",
      medicationName: "Lisinopril",
      dosage: "5mg",
      scheduledTime: twoDaysAgo
    };

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_very_old" }),
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.summary.skipped, "dose_too_old");
  }

  // Test 34: Missing scheduledTime field returns 400
  {
    console.log("Test 34: Missing scheduledTime field returns 400");
    mockFirestore.doseEvents["patient_uid_1/dose_no_time"] = {
      status: "MISSED",
      medicationName: "Warfarin",
      dosage: "2mg",
      scheduledTime: null
    };

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_no_time" }),
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 400);
    const body = await res.json();
    assert.ok(body.error && body.error.includes("scheduledTime"), "Error must mention scheduledTime");
  }

  // Test 35: scheduledTime = 0 returns 400 (zero is treated as invalid)
  {
    console.log("Test 35: scheduledTime = 0 returns 400");
    mockFirestore.doseEvents["patient_uid_1/dose_zero_time"] = {
      status: "MISSED",
      medicationName: "Metformin",
      scheduledTime: 0
    };

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_zero_time" }),
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 400);
    const body = await res.json();
    assert.ok(body.error && body.error.includes("scheduledTime"));
  }

  // Test 36: Stale dose creates no delivery lock records in Firestore
  {
    console.log("Test 36: Stale dose creates no delivery lock records in Firestore");
    mockFirestore.deliveries = {};
    sentFcmMessages = [];
    const oldTime = Date.now() - (8 * 60 * 60 * 1000); // 8h ago
    mockFirestore.doseEvents["patient_uid_1/dose_8h_old"] = {
      status: "MISSED",
      medicationName: "Atorvastatin",
      scheduledTime: oldTime
    };
    mockFirestore.links = [
      { patientUid: "patient_uid_1", caregiverUid: "cg_1", status: "active" }
    ];

    const token = await createTestJwt({}, { sub: "patient_uid_1" });
    const req = new Request("https://example.com/notify", {
      method: "POST",
      body: JSON.stringify({ doseCloudId: "dose_8h_old" }),
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` }
    });
    const res = await worker.fetch(req, fullEnv);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.summary.skipped, "dose_too_old");
    assert.strictEqual(Object.keys(mockFirestore.deliveries).length, 0, "No delivery records must be created for stale doses");
    assert.strictEqual(sentFcmMessages.length, 0, "No FCM messages must be sent for stale doses");
  }

  // Restore real fetch
  globalThis.fetch = realFetch;

  console.log("\n=======================================================");
  console.log("ALL 36 AUTOMATED UNIT & INTEGRATION TESTS PASSED!");
  console.log("=======================================================");
}

runTests().catch(err => {
  console.error("Test failed:", err);
  process.exit(1);
});