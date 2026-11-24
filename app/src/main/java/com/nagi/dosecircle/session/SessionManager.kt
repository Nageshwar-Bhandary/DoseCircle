
package com.nagi.dosecircle.session

import com.google.firebase.auth.FirebaseAuth
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionManager(
    private val personRepository: PersonRepository,
    private val alarmScheduler: AlarmScheduler,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _currentPersonId = MutableStateFlow<Long?>(null)
    val currentPersonId: StateFlow<Long?> = _currentPersonId.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser

        scope.launch {
            if (user != null) {
                val previousPersonId = _currentPersonId.value

                val person = personRepository.getOrCreatePersonForUser(
                    uid = user.uid,
                    email = user.email,
                    displayName = user.displayName
                )

                _currentPersonId.value = person.id

                // On login, reschedule that person's alarms.
                if (previousPersonId != person.id) {
                    alarmScheduler.scheduleAlarmsForPerson(person.id)
                }
            } else {
                val previousPersonId = _currentPersonId.value

                if (previousPersonId != null) {
                    // On logout, cancel all alarms for that person.
                    alarmScheduler.cancelAlarmsForPerson(previousPersonId)
                }

                _currentPersonId.value = null
            }
        }
    }

    init {
        firebaseAuth.addAuthStateListener(authStateListener)
    }
}
