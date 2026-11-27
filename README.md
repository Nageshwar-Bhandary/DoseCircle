# DoseCircle â€“ Caregiver Notification Subsystem

This document provides architectural and operational documentation for the Missed-Dose Caregiver Notification system in DoseCircle.

---

## 1. End-to-End Notification Flow

```
[Patient Device]
       │
       ▼ (1) Dose marked MISSED (Alarm / Sweep Worker)
[DoseEventSyncRepository]
       │
       ▼ (2) Syncs event to Firestore: users/{patientUid}/doseEvents/{doseCloudId}
       │
       ▼ (3) Calls CaregiverNotificationClient.notifyMissedDose(doseCloudId)
       │     (with Firebase ID token & exponential-backoff WorkManager retry)
       │
       ▼ (4) POST /notify to Cloudflare Worker
┌────────────────────────────────────────────────────────┐
│ Cloudflare Worker (Notification Dispatcher)             │
│                                                        │
│  ├─ Verify RS256 Firebase ID token via Google JWKS    │
│  ├─ Verify dose ownership & status == "MISSED"         │
│  ├─ 6-Hour Stale-Dose Cutoff check                     │
│  ├─ Query active linked caregivers (status == "active")│
│  ├─ Atomic delivery lock: notifyDeliveries/{id}        │
│  └─ Dispatch FCM HTTP v1 (Android HIGH priority)       │
└────────────────────────────────────────────────────────┘
       │
       ▼ (5) FCM Push Notification (Data-Only)
[Caregiver Device: DoseCircleMessagingService]
       │
       ├─ (6) Check SharedPreferences duplicate suppression (stableNotificationId)
       ├─ (7) Display Heads-Up notification on channel 'caregiver_alerts'
       └─ (8) Persist stableNotificationId to SharedPreferences
```

### Flow Steps:
1. **Dose Transition**: When a scheduled dose is missed, `MissedDoseWorker` or `MissedSweepWorker` marks the local dose event as `MISSED`.
2. **Cloud Sync**: `DoseEventSyncRepository` synchronizes the missed event to Firestore at `users/{patientUid}/doseEvents/{doseCloudId}`.
3. **Dispatch Trigger**: Upon successful sync, `CaregiverNotificationClient` retrieves the patient's fresh Firebase ID token and sends a POST request to the Cloudflare Worker `/notify` endpoint with `{ doseCloudId }`.
4. **Retry Guarantee**: If network dispatch fails or times out, `MissedDoseNotificationRetryWorker` schedules retries using WorkManager with exponential backoff (up to 5 attempts).
5. **Worker Verification**:
   - Authenticates the patient's Firebase ID token against Google's public certificates.
   - Validates that the caller owns the dose event and the event status is `MISSED`.
   - Rejects stale doses scheduled more than 6 hours ago (`dose_too_old`).
   - Resolves all caregivers linked with `status == "active"`.
6. **Atomic Server Claims**: For each caregiver, attempts an atomic Firestore transaction on `notifyDeliveries/{doseCloudId}_{caregiverUid}`.
7. **FCM Dispatch**: Dispatches an FCM HTTP v1 data-only message with `priority: "HIGH"`.
8. **Client Display & Deduplication**: The caregiver's `DoseCircleMessagingService` receives the push, checks `caregiver_delivered_notifications` SharedPreferences, posts the notification, and then records the delivery ID.

---

## 2. Cloudflare Worker Configuration

The notification dispatcher runs as a serverless Cloudflare Worker (`worker/src/index.js`).

### Environment Variables & Secrets:
- `FIREBASE_PROJECT_ID`: Configured in `wrangler.toml` (`dosecircle-6cc09`).
- `FIREBASE_SERVICE_ACCOUNT`: Configured as an encrypted Cloudflare Worker secret. Contains the full Google Cloud Service Account JSON credentials with roles for Firestore and Firebase Cloud Messaging.

### Service Account Permissions:
- `https://www.googleapis.com/auth/datastore` (Firestore REST API: transactions, reads, writes)
- `https://www.googleapis.com/auth/firebase.messaging` (FCM HTTP v1 message dispatch)

### Local Testing:
```bash
cd worker
npm test
```
The Worker test suite includes 36 automated unit and integration tests covering JWT verification, lease concurrency, lease expiration/recovery, and stale-dose cutoff.

---

## 3. Security Model

1. **Zero Credential Exposure**:
   - No service account keys, private keys, or API secrets are committed to Git or packaged in the Android APK.
   - Cloudflare secrets are managed strictly via Wrangler CLI or Cloudflare dashboard.
2. **Deterministic Ownership Validation**:
   - The Worker validates that `sub` in the caller's JWT matches the `patientUid` owning the dose event document in Firestore.
   - Users cannot trigger notifications for dose events belonging to other patients.
3. **Firestore Security Rules**:
   - The delivery tracking collection `notifyDeliveries/{deliveryId}` denies all client reads and writes:
     ```
     match /notifyDeliveries/{deliveryId} {
       allow read, write: if false;
     }
     ```
   - Only backend processes authenticated via Firebase Admin / Google Service Account can read or write delivery records.
4. **Data-Only Push Payloads**:
   - Push notifications use data-only FCM messages, preventing automatic background display of unverified payloads by Google Play Services.
   - The Android client strictly validates and controls notification creation and deduplication.

---

## 4. Multi-Layer Deduplication

To guarantee that caregivers receive timely alerts without annoying duplicates:

### Server-Side Deduplication (`notifyDeliveries/{doseCloudId}_{caregiverUid}`):
- Uses Firestore transactions with optimistic locking (`currentDocument.updateTime` and `currentDocument.exists: false`).
- **60-Second In-Flight Lease**: Sets `status: "sending"` with a 60-second lease timestamp. Concurrent requests receive `skipped: "in_flight"`.
- **Permanent Sent State**: Once FCM returns success, the document is updated to `status: "sent"`. Any subsequent dispatch attempt returns `skipped: "already_sent"`.
- **Lease Expiry Recovery**: If a worker isolate crashes during FCM dispatch, expired leases (>60 seconds) are reclaimed and safely retried.

### Client-Side Deduplication (`caregiver_delivered_notifications`):
- Stored in SharedPreferences (`caregiver_delivered_notifications.xml`).
- Key: `stableNotificationId` format `${doseCloudId}_${caregiverUid}`.
- Value: Timestamp of successful delivery display.
- **Crash Safety**: The notification is posted to `NotificationManager.notify()` *before* the ID is saved to SharedPreferences. If the app crashes prior to display, subsequent FCM retries will successfully display the alert.
- **Retention**: Records are kept for 24 hours to prevent duplicate displays from late FCM retries, with automatic cleanup of entries older than 48 hours.

---

## 5. 6-Hour Stale-Dose Cutoff

To protect caregivers from notification storms during data restores, long offline periods, or database synchronization:
- The Worker checks the `scheduledTime` of the dose event against current time.
- If `Date.now() - scheduledTime > 6 * 60 * 60 * 1000` (6 hours):
  - Dispatch is skipped immediately with `summary.skipped: "dose_too_old"`.
  - No `notifyDeliveries` lock documents are created.
  - No FCM messages are dispatched.
  - Returns HTTP 200 with `ok: true` and descriptive message.
- If `scheduledTime` is missing or non-positive, the Worker rejects the request with HTTP 400.

---

## 6. App State Handling: Swiped from Recents vs. Force-Stop

Android handles background FCM delivery differently depending on process and task state:

| State | App Process | FCM Data Message Behavior | Notification Display |
|---|---|---|---|
| **Foreground** | Running | Received by `DoseCircleMessagingService.onMessageReceived()` | â Displayed via `caregiver_alerts` channel |
| **Background** (Home pressed) | Sleeping / Cached | Received by `DoseCircleMessagingService.onMessageReceived()` | â Displayed via `caregiver_alerts` channel |
| **Swiped from Recents** | Process killed | OS launches application process in background to handle FCM message | â Displayed via `caregiver_alerts` channel |
| **Force-Stopped** (App Settings) | Stopped state | OS blocks all broadcasts and FCM messages to package until user manually reopens app | â Blocked by OS until manual app relaunch |
| **Logged Out** | Active / Idle | FCM token deleted from Firestore by `FcmTokenManager.deleteTokenOnLogout()` | â Worker finds no FCM token; delivery skipped |
| **Pending Link** | Active / Idle | Worker query matches only `status == "active"` links | â Caregiver excluded from delivery list |

