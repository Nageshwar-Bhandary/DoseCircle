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
- [x] **Medicine Management**: Add, edit, view, and delete medications with dosage, instructions, photo attachment, and stock counts.
- [x] **Alarm & Reminder Engine**: Exact alarms using Android `AlarmManager` with fullscreen reminder alerts, customizable reminder times, and follow-up alerts for unacknowledged doses.
- [x] **Dose Event Tracking**: Track taken, skipped, and missed medication events with exact timestamps.
- [x] **Medication History**: Comprehensive audit log of past dose actions filterable by date and status.
- [x] **Stock Management & Refill Warnings**: Automatic stock decrement on taken doses and proactive low-stock warnings when medicine counts fall below configured thresholds.
- [x] **Multi-Profile Support**: Local profile management (`persons` table) allowing multiple individuals to manage schedules independently on a single device.
- [x] **Cloud Sync & Account Restore**: Automatic background synchronization of profiles, medications, and dose events to Google Cloud Firestore, with full profile and data restore upon sign-in.
- [x] **Caregiver Account Linking**: Secure invite code generation with 10-minute Firestore Timestamp expiry, pending link creation, and patient approval flow.
- [x] **Caregiver Dashboard**: Real-time caregiver monitoring interface displaying patient adherence, active schedules, and recent dose events.
- [x] **Missed-Dose Caregiver Notification Subsystem**:
  - Cloudflare Worker serverless dispatcher (`dosecircle-notify`) validating Firebase ID tokens and communicating with FCM HTTP v1 using Google OAuth assertion grant (RFC 7523).
  - Server-side atomic delivery lease and duplicate suppression in top-level `notifyDeliveries` collection.
  - 6-hour stale-dose cutoff protecting caregivers from historical alert storms during backup restores.
  - Client-side persistent deduplication via `SharedPreferences` in `DoseCircleMessagingService`.
  - Android retry sync via WorkManager (`MissedDoseNotificationRetryWorker`) with exponential backoff for transient network interruptions.
- [x] **Authentication**:
  - Email & Password sign-up and sign-in with Firebase Authentication.
  - Google Sign-In using Android Credential Manager (`GoogleAuthClient`).
  - Password reset via Firebase Authentication.
- [x] **Bottom Navigation**: Persistent bottom navigation bar across main app sections.

### Optional & Planned Features
- [ ] **Activity Screen**: Dedicated UI screen displaying daily activity, pedometer tracking, and step progress.
- [ ] **Senior Mode**: High-contrast, simplified UI with enlarged touch targets and streamlined navigation for elderly users.
- [ ] **App Theme**: Comprehensive dynamic color theming, healthcare-specific palettes, and dark mode customization.
- [ ] **Prescription OCR via ML Kit**: Automatic extraction of medication name, dosage, and frequency from prescription images.
- [ ] **Activity Analytics & Trends**: Visual charts and weekly/monthly adherence percentage analytics.
- [ ] **PDF Reports**: Exportable medication and adherence reports for sharing with healthcare providers.

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

- **Exact Alarms & Permissions**:
  - Android 12+ (API 31+) and Android 14+ (API 34+) require exact alarm permissions (`SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`) to fire medication reminder alarms at exact clock times.
  - When exact alarm permission is unavailable or denied, DoseCircle gracefully falls back to inexact alarms via `AlarmManager.setAndAllowWhileIdle()` and displays a small dismissible banner on the Today screen directing the user to system settings.
  - **Google Play Policy on `USE_EXACT_ALARM`**: Under Google Play policies, `USE_EXACT_ALARM` is restricted to qualifying alarm clock and calendar applications; Google Play may reject apps that declare this permission without meeting specific core-use criteria. Because DoseCircle is distributed directly via APK builds rather than the Google Play Store, declaring `USE_EXACT_ALARM` (alongside `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`) ensures reliable exact alarm scheduling across modern Android versions without requiring manual user toggling in settings on supported builds.
  - Android 13+ (API 33+) requires runtime `POST_NOTIFICATIONS` permission to show heads-up alerts and caregiver notification banners.
- **Battery Optimization & OEM Restrictions**:
  - Device manufacturers with customized Android flavors (such as realme / ColorOS, Xiaomi / MIUI / HyperOS, OnePlus / OxygenOS, Huawei / EMUI) aggressively restrict background tasks.
  - For continuous alarm delivery, users on these devices must set DoseCircle's battery usage to **"Unrestricted"** and enable **"Auto-start"** / **"Allow background activity"**.
- **Force-Stopped App Limitation**:
  - In accordance with core Android OS security design, if an application is force-stopped by the user via Android Settings (`Settings > Apps > Force Stop`), the OS places the app in a stopped state. All scheduled alarms, WorkManager jobs, and FCM background delivery are blocked until the user manually relaunches the app.
- **Six-Hour Stale-Dose Cutoff**:
  - The Cloudflare Worker enforces a strict 6-hour cutoff (`STALE_CUTOFF_MS = 6 * 60 * 60 * 1000`). If a missed dose was scheduled more than 6 hours in the past (e.g. during a backup restore or extended offline period), notification dispatch is skipped (`skipped: "dose_too_old"`) to avoid inundating caregivers with outdated alerts.
- **At-Least-Once Delivery & Duplicate Suppression**:
  - FCM push messaging follows an at-least-once delivery contract. To prevent caregivers from receiving duplicate notification banners, DoseCircle applies dual-layer suppression:
    1. *Server-Side*: Firestore `notifyDeliveries` atomic transaction locks prevent multiple FCM dispatches for the same dose event.
    2. *Client-Side*: `DoseCircleMessagingService` checks persistent `SharedPreferences` cache for `stableNotificationId` before posting notification banners.
- **Online Patient Requirement for Escalation**:
  - Missed-dose escalation requires that the patient's device be powered on and connected to the internet. The local alarm or `MissedSweepWorker` marks the dose as missed and initiates the sync to Firestore and Cloudflare Worker. If a patient's phone is powered off or disconnected, missed-dose escalation cannot trigger until the device reconnects.

---

## 8. Team

- **Nageshwar Bhandary**
- **Spoorthi**
