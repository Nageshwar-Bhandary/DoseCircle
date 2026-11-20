package com.nagi.dosecircle

import android.app.Application
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository

class DoseCircleApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val personRepository: PersonRepository by lazy {
        PersonRepository(database.personDao())
    }

    val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(database)
    }
}
