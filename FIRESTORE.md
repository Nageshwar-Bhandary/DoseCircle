# DoseCircle – Firestore Structure

## 1. Database Structure

| Collection / Path | Fields | Access |
|---|---|---|
| `users/{uid}` | `displayName`, `email`, `fcmToken` | Owner read/write; linked caregivers read |
| `users/{uid}/medications/{id}` | Medication details and schedule | Owner read/write; linked caregivers read |
| `users/{uid}/doseEvents/{id}` | `medicationName`, `scheduledTime`, `status`, `actionTime` | Owner read/write; linked caregivers read |
| `inviteCodes/{code}` | `patientUid`, `createdAt`, `expiresAt` | Signed-in users can get a code by exact ID; listing denied; patient can create/delete |
| `links/{patientUid_caregiverUid}` | `patientUid`, `caregiverUid`, `code`, `status`, names, email | Only involved patient and caregiver |
| `notifyDeliveries/{deliveryId}` | `status`, `updatedAt`, `attempts`, `sentAt`, `fcmMessageId`, `error` | Server-only (Worker Service Account); client access denied (`allow read, write: if false;`) |

## 2. Caregiver Linking Flow

1. Patient generates an 8-character invite code.
2. The invite code expires after 10 minutes.
3. The expiry is stored as a Firestore Timestamp (`expiresAt`).
4. Caregiver enters the invite code.
5. A pending link is created using the invite code.
6. Patient reviews caregiver name/email and approves the request.
7. Link status changes from `pending` to `active`.
8. Invite code is deleted after patient approval.

## 3. Security Rules

> **Note on Security Rules Management**:
> - Firebase Console is the source of truth for published rules.
> - The root `firestore.rules` file is the version-controlled copy.
> - Whenever rules change in Firebase Console, this file must also be updated.

- Users can access their own data.
- Linked caregivers can read the patient's data.
- Only the patient can modify their medication and dose event data.
- Invite codes cannot be listed.
- Expired invite codes cannot be retrieved.
- Only the patient can approve a pending caregiver link.
- Only the involved patient and caregiver can access their link.
- Link approval is restricted to changing the status from `pending` to `active`.
- `notifyDeliveries/{deliveryId}`: All direct client read and write operations are strictly denied (`allow read, write: if false;`). Dispatches and delivery tracking are performed exclusively by the backend Notification Dispatcher Worker via Firebase Admin/Service Account credentials.

## 4. Invite Code Expiry

- Invite codes are valid for 10 minutes in the application.
- Firestore rules allow a maximum expiry of 1 day, while the application uses the shorter 10-minute limit.
- `expiresAt` is stored as a Firestore Timestamp.

## 5. Database Configuration

- **Database:** `(default)`
- **Edition:** Standard
- **Region:** `asia-south1` (Mumbai)
