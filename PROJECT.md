# DoseCircle

Smart Medication Reminder & Caregiver Support App.

An Android application built using Kotlin, Jetpack Compose and MVVM architecture.

## Technology Stack
- Kotlin
- Jetpack Compose
- MVVM Architecture
- Room Database (SQLite)
- KSP
- Navigation Compose
- WorkManager
- AlarmManager
- Firebase Authentication and Firestore (later phase)
- Google ML Kit (later phase)
- Android SensorManager
- StateFlow

## Development Rules
- Everything must be free. No paid services.
- Do not use Hilt. Use simple ViewModels and repositories.
- Minimum SDK: API 26.
- Use StateFlow in ViewModels.
- Follow MVVM architecture.
- Every database table must include personId from the beginning.
- Prioritize offline functionality.
- Work on ONE task at a time.
- Do not implement features I have not requested.
- Explain code changes briefly after every task.
- Do not overwrite existing working code unnecessarily.
- Test the project after meaningful changes.

## MVP Development Roadmap

### Phase 1: Core Features
1. Medicine management (Add, Edit, Delete).
2. Medication reminders using AlarmManager.
3. Taken, Skipped and Missed dose tracking.
4. Medication history.
5. Medicine stock and refill alerts.
6. Step counter and daily activity tracking.
7. Multiple profiles.
8. Senior Mode.

### Phase 2: Advanced Features
1. Prescription OCR using ML Kit.
2. Caregiver account linking.
3. Missed-dose escalation and caregiver notifications.
4. Cloud synchronization.
5. Activity analytics and reports.
6. PDF reports.

## Important Instructions
- Complete one development task before moving to another.
- Explain every implementation briefly.
- Do not introduce unnecessary dependencies.
- Maintain clean and modular code.
- Ensure the app builds successfully after each major change.
