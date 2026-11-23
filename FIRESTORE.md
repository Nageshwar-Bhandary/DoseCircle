# DoseCircle – Firestore Structure

## 1. Database Structure

| Collection / Path | Fields | Access |
|---|---|---|
| `users/{uid}` | `displayName`, `email`, `fcmToken` | Owner read/write; linked caregivers read |
| `users/{uid}/medications/{id}` | Medication details and schedule | Owner read/write; linked caregivers read |
| `users/{uid}/doseEvents/{id}` | `medicationName`, `scheduledTime`, `status`, `actionTime` | Owner read/write; linked caregivers read |
| `inviteCodes/{code}` | `patientUid`, `createdAt`, `expiresAt` | Signed-in users can get a code by exact ID; listing denied; patient can create/delete |
| `links/{patientUid_caregiverUid}` | `patientUid`, `caregiverUid`, `status`, names, email | Only involved patient and caregiver |

## 2. Caregiver Linking Flow

1. Patient generates an 8-character invite code.
2. Code expires after 10 minutes.
3. Caregiver enters the invite code.
4. A pending link is created.
5. Patient reviews caregiver name/email and approves the request.
6. Link status changes from `pending` to `active`.
7. Invite code is deleted after approval.

## 3. Security Rules

- Users can access their own data.
- Linked caregivers can read the patient's data.
- Only the patient can modify their medication and dose event data.
- Invite codes cannot be listed.
- Expired invite codes cannot be retrieved.
- Only the patient can approve a pending caregiver link.
- Only the involved patient and caregiver can access their link.

## 4. Expiry

Invite codes are valid for 10 minutes in the application. Firestore rules allow a maximum expiry of 1 day, but the application uses the shorter 10-minute limit.

`expiresAt` is stored as a Firestore Timestamp.

## 5. Database Region

- Database: `(default)`
- Edition: Standard
- Region: `asia-south1` (Mumbai)
