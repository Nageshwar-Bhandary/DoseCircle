# DoseCircle

Smart Medication Reminder & Caregiver Support App for Android.

DoseCircle is a native Android application built with Kotlin and Jetpack Compose that helps patients stay on track with their medication schedules and keeps linked caregivers informed in real time when doses are missed.

---

## 1. What DoseCircle Does

DoseCircle bridges the gap between patient medication adherence and caregiver peace of mind through two connected workflows:

### Patient Flow
- **Medication Management**: Patients create and manage their medications, entering dosage details, instructions, stock quantities, and custom daily reminder schedules.
- **Reliable Reminders**: At scheduled dose times, the app triggers exact alarms and full-screen reminders even if the screen is off or the app is closed.
- **Adherence Tracking**: Patients mark scheduled doses as **Taken** or **Skipped**. If a dose window elapses without acknowledgment, the dose is automatically recorded as **Missed**.
- **Caregiver Linking**: Patients generate a secure, 8-character, time-limited (10-minute) invite code and approve caregiver link requests directly within the app.
- **Cloud Sync**: Patient medications, reminder schedules, and dose events automatically synchronize to Cloud Firestore.

### Caregiver Flow
- **Seamless Pairing**: Caregivers enter the patient's invite code to submit a link request. Once approved by the patient, the caregiver gains read access to the patient's medication regimen.
- **Caregiver Dashboard**: Caregivers can view patient medications, current adherence history, and dose event logs in real time.
- **Automated Missed-Dose Push Notifications**: When a patient misses a dose, DoseCircle automatically dispatches a high-priority push notification to all active caregivers via a serverless Cloudflare Worker, alerting them immediately so they can follow up.

---

## 2. Features

### Completed Features
- [x] **Bottom Navigation & Today Home Screen**: Persistent bottom navigation bar across main app sections with a comprehensive Today schedule view, summary metrics, and reminder shortcuts.
- [x] **Senior Mode**: High-contrast, accessible UI mode with enlarged typography, increased touch target padding, and prominent action buttons for elderly users.
- [x] **Follow-Up Reminders**: Fullscreen alerts and follow-up reminders for unacknowledged doses to ensure timely medication adherence.
- [x] **Refill Alerts & Stock Management**: Proactive low-stock warnings when medicine counts fall below configured thresholds and automatic stock decrements on taken doses.
- [x] **Medicine History**: Comprehensive audit log of past dose actions (taken, skipped, missed) filterable by date and status.
- [x] **Caregiver Dashboard**: Real-time caregiver monitoring interface displaying patient adherence, active schedules, and recent dose events.
- [x] **Missed-Dose Push Notifications to Caregivers**: Automated high-priority push notifications dispatched to linked caregivers via serverless Cloudflare Worker (`dosecircle-notify`) and FCM HTTP v1 when a dose is missed.
- [x] **Restore on a New Phone**: Automatic cloud synchronization of profiles, medications, and dose events to Google Cloud Firestore, with full profile and data restore upon sign-in.
- [x] **Exact-Alarm Fallback for Android 12 and Above**: Reliable medication reminder scheduling using `AlarmManager` with graceful fallback to `setAndAllowWhileIdle()` when exact alarm access is unavailable, accompanied by a dismissible warning banner on the Today screen directing users to system settings.
- [x] **Medicine Management**: Add, edit, view, and delete medications with dosage, instructions, photo attachment, and stock counts.
- [x] **Multi-Profile Support**: Local profile management (`persons` table) allowing multiple individuals to manage schedules independently on a single device.
- [x] **Caregiver Account Linking**: Secure invite code generation with 10-minute Firestore Timestamp expiry, pending link creation, and patient approval flow.
- [x] **Authentication**:
  - Email & Password sign-up and sign-in with Firebase Authentication.
  - Google Sign-In using Android Credential Manager (`GoogleAuthClient`).
  - Password reset via Firebase Authentication.

### Pending Real-Device Testing
- **Activity Screen (PR #20)**: Dedicated UI screen displaying daily activity, hardware step counter tracking (`SensorManager`), and step goals. Implementation is submitted in PR #20 and is awaiting real-device hardware verification before merging into `main`.

### Optional & Planned Features
- [ ] **Prescription OCR via ML Kit**: Automatic extraction of medication name, dosage, and frequency from prescription images.
- [ ] **Activity Analytics & Trends**: Visual charts and weekly/monthly adherence percentage analytics.
- [ ] **PDF Reports**: Exportable medication and adherence reports for sharing with healthcare providers.
- [ ] **App Theme**: Comprehensive dynamic color theming, healthcare-specific palettes, and dark mode customization.

---

## 3. Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Architecture**: MVVM Architecture with reactive `StateFlow` and clean Repository pattern (no third-party DI frameworks like Hilt; clean manual injection).
- **Local Database**: Room Database (SQLite) with KSP annotation processing and version-controlled schema export (`app/schemas/com.nagi.dosecircle.data.database.AppDatabase/4.json`).
- **Background Scheduling**:
  - `AlarmManager`: Exact alarms for dose reminders.
  - `WorkManager`: Background periodic sync (`MissedSweepWorker`) and retry queues (`MissedDoseNotificationRetryWorker`).
  - `SensorManager`: Hardware step counter sensor for pedometer activity tracking.
- **Backend & Cloud Infrastructure**:
  - **Firebase Authentication**: User identity with Email/Password and Google Credential Manager integration.
  - **Cloud Firestore**: Real-time cloud database for user profiles, medications, dose events, invite codes, and caregiver links (`asia-south1` Mumbai).
  - **Firebase Cloud Messaging (FCM)**: Push notification delivery to caregiver devices (Android HIGH priority data messages).
  - **Cloudflare Workers**: Serverless dispatcher (`dosecircle-notify`) running on Cloudflare's edge network, executing secure server-to-server calls to Firestore and FCM HTTP v1 using RS256 JWT signing.
- **Cost & Quotas**:
  - The project operates entirely on **free tiers**:
    - Firebase Spark Plan (free authentication, Firestore operations within free daily quotas).
    - Cloudflare Workers Free Tier (up to 100,000 requests/day).
  - All deployments are subject to provider limits and fair use quotas.

---

## 4. Setup & Installation

### Prerequisites
- Android Studio Ladybug or newer.
- Android SDK API 34 (Minimum SDK: API 26, Target SDK: API 34).
- JDK 17 (recommended for Gradle builds).
- Node.js 18+ and npm (for the Cloudflare Worker).

### 1. Clone the Repository
```bash
git clone https://github.com/Nageshwar-Bhandary/DoseCircle.git
cd DoseCircle
```

### 2. Configure Firebase
1. Open the [Firebase Console](https://console.firebase.google.com/) and create a new Firebase project (or use an existing one).
2. Register an Android application with the package name:
   ```
   com.nagi.dosecircle
   ```
3. Generate and register the **debug SHA-1 fingerprints**:
   - Both **Nageshwar's** and **Spoorthi's** debug SHA-1 fingerprints must be registered in the Firebase Console so Google Sign-In functions seamlessly across both development environments and test devices.
   - Run the signing report to obtain the SHA-1 fingerprint for your local machine:
   ```bash
   # On Linux/macOS
   ./gradlew signingReport

   # On Windows PowerShell
   .\gradlew.bat signingReport
   ```
4. Download `google-services.json` from Firebase Console and place it at:
   ```
   app/google-services.json
   ```
   > [!WARNING]
   > `app/google-services.json` is required locally for Android builds to configure Firebase, but it must **never be committed to Git**. Ensure it remains excluded via `.gitignore` and is never tracked in the repository.

### 3. Enable Authentication Providers
In Firebase Console under **Build > Authentication > Sign-in method**, enable:
- **Email/Password**: Enable Email/Password provider.
- **Google**: Enable Google sign-in. Ensure the Web Client ID generated by Firebase matches the client ID configured in the app for Android Credential Manager.

### 4. Configure Cloud Firestore
1. In Firebase Console under **Build > Firestore Database**, create a database in default mode (`(default)`), selecting your preferred region (e.g., `asia-south1`).
2. Deploy the version-controlled security rules located in `firestore.rules`:
   - Open Firebase Console > Firestore Database > **Rules** tab.
   - Copy the exact contents of `firestore.rules` and click **Publish**.

---

## 5. Cloudflare Worker Setup

The missed-dose caregiver notification dispatcher runs as a serverless Cloudflare Worker located in the `worker/` directory.

### 1. Worker Configuration
The worker configuration is managed in `worker/wrangler.toml`:
```toml
name = "dosecircle-notify"
main = "src/index.js"
compatibility_date = "2026-11-27"

[vars]
FIREBASE_PROJECT_ID = "dosecircle-6cc09"
```
Update `FIREBASE_PROJECT_ID` to match your Firebase project ID if using a custom project.

### 2. Authenticate Wrangler
```bash
cd worker
npx wrangler login
```

### 3. Configure the Firebase Service Account Secret
The Worker requires a Google Cloud Service Account key with permissions to read Firestore documents and dispatch FCM notifications. The exact roles configured and tested for this project are:
- **Cloud Datastore User** (`roles/datastore.user`)
- **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`)

In Google Cloud Console (IAM & Admin) or Firebase Console > Project Settings > Service accounts, generate a new private key JSON for this service account.

Store the secret in Cloudflare using Wrangler:
```bash
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT
```
When prompted, paste the **entire contents** of your service account JSON file.

> [!CAUTION]
> The service account JSON file and private keys must never be committed, logged, exposed, or pushed to source control, but may be provided directly to the Wrangler secret prompt (`wrangler secret put`).

### 4. Deploy the Worker
```bash
# Verify local tests first
npm test

# Deploy to Cloudflare Workers
npx wrangler deploy
```
Wrangler will output the published production URL:
```
https://dosecircle-notify.<your-subdomain>.workers.dev/notify
```
If using a custom deployment URL, update `DEFAULT_WORKER_URL` in `app/src/main/java/com/nagi/dosecircle/notifications/network/CaregiverNotificationClient.kt`.

---

## 6. Running Tests

### Android Unit Tests
To run all local JVM unit tests (database, ViewModels, repositories, and notification components):
```bash
# Linux / macOS
./gradlew testDebugUnitTest

# Windows PowerShell
.\gradlew.bat testDebugUnitTest
```

### Android Debug Build Assembly
To compile and assemble the debug APK:
```bash
# Linux / macOS
./gradlew assembleDebug

# Windows PowerShell
.\gradlew.bat assembleDebug
```
The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Cloudflare Worker Tests
The Worker test suite consists of 36 automated unit and mock integration tests covering token verification, lease acquisition, duplicate suppression, stale-dose cutoff, and FCM payload formation:
```bash
cd worker
npm test
```

---

## 7. Known Limitations & Operating Constraints

1. **Local-Only Medicine Photos**:
   - Medicine photos attached to medication records are stored locally on the patient's device only. They are not uploaded to Firestore, are not restored on a new phone during account restore, and cannot be viewed by caregivers on the Caregiver Dashboard.
2. **Exact Alarms & Direct APK Distribution**:
   - Under Google Play policies, declaring `USE_EXACT_ALARM` is restricted to qualifying alarm clock and calendar applications, and apps that do not meet strict core-use criteria may be rejected from the Play Store. Because DoseCircle is distributed directly via APK builds rather than the Google Play Store, declaring `USE_EXACT_ALARM` (with `SCHEDULE_EXACT_ALARM` restricted to `maxSdkVersion="32"`) ensures reliable exact alarm scheduling across modern Android versions (Android 12+ and 14+) without requiring manual user toggles in Settings.
3. **Exact-Alarm Fallback & Permission-Change Receiver**:
   - When exact-alarm permission is unavailable or denied, DoseCircle gracefully falls back to inexact alarms via `AlarmManager.setAndAllowWhileIdle()` and displays a small dismissible banner on the Today screen directing the user to system settings. The `ExactAlarmPermissionReceiver` is implemented and registered in the manifest to reschedule alarms when permissions change, but because Android 14 automatically grants `USE_EXACT_ALARM` at install time without sending runtime transition broadcasts, runtime verification of the permission-change receiver on API 31/32 remains outstanding.
4. **Force-Stopped App Limitation**:
   - In accordance with core Android OS security architecture, if an application is force-stopped by the user via system settings (`Settings > Apps > Force Stop`), the OS places the app in a stopped state. All scheduled alarms, WorkManager background jobs, and caregiver alerts cannot be dispatched or delivered until the user manually relaunches the app.
5. **Six-Hour Stale-Dose Cutoff**:
   - The Cloudflare Worker notification dispatcher enforces a strict 6-hour cutoff (`STALE_CUTOFF_MS = 6 * 60 * 60 * 1000`). If a missed dose was scheduled more than 6 hours in the past (e.g. during extended offline periods or historical backup restores), notification dispatch is skipped (`skipped: "dose_too_old"`) to avoid inundating caregivers with obsolete alerts.
6. **At-Least-Once Delivery & Duplicate Suppression**:
   - FCM push messaging follows an at-least-once delivery contract. To prevent caregivers from receiving duplicate notification banners, DoseCircle applies dual-layer suppression:
     - *Server-Side*: Atomic Firestore transactions in the `notifyDeliveries` collection acquire exclusive delivery leases and record completion states before dispatching FCM payloads.
     - *Client-Side*: `DoseCircleMessagingService` maintains a persistent deduplication cache in `SharedPreferences` for `stableNotificationId` before posting notification banners.
7. **Online Patient Requirement for Escalation**:
   - Missed-dose escalation requires that the patient's device be powered on and connected to the internet. Detection occurs via local alarm triggers or periodic `MissedSweepWorker` checks that record the missed status in Firestore and invoke the Cloudflare Worker dispatcher. If a patient's phone is powered off or disconnected from the internet, missed doses cannot be detected or escalated until the device powers back on and reconnects.
8. **OEM Battery Optimization & Background Execution**:
   - Device manufacturers with aggressive power-management implementations (such as realme / OPPO ColorOS, Xiaomi MIUI / HyperOS, OnePlus OxygenOS, Huawei EMUI, Samsung One UI) restrict background processes and alarms. To ensure reliable alarm delivery and background synchronization, users on these devices may need to configure DoseCircle's app info settings to set Battery usage to **"Unrestricted"** and enable **"Auto-start"** / **"Allow background activity"**.
9. **Room Database Schema Migrations**:
   - The local Room SQLite database maintains a versioned schema export (`app/schemas/com.nagi.dosecircle.data.database.AppDatabase/`). Any changes to Room entities or database structure require incrementing the database version in `AppDatabase.kt` and writing an explicit `Migration` path to prevent runtime crashes or data loss during app updates.

---

## 8. Team

- **Nageshwar Bhandary**
- **Spoorthi**
