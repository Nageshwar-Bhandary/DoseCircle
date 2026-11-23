package com.nagi.dosecircle.data.repository

import com.nagi.dosecircle.data.dao.PersonDao
import com.nagi.dosecircle.data.entity.Person
import kotlinx.coroutines.flow.Flow

class PersonRepository(private val personDao: PersonDao) {

    fun getAllPersons(): Flow<List<Person>> {
        return personDao.getAllPersons()
    }

    suspend fun ensureDefaultPerson(): Person {
        val existingDefault = personDao.getDefaultPerson()
        if (existingDefault != null) {
            return existingDefault
        }
        val defaultPerson = Person(name = "Me", isDefault = true)
        val generatedId = personDao.insertPerson(defaultPerson)
        return defaultPerson.copy(id = generatedId)
    }

    suspend fun getOrCreatePersonForUser(
        uid: String,
        email: String?,
        displayName: String?
    ): Person {
        val existing = personDao.getByCloudUid(uid)
        if (existing != null) {
            return existing
        }

        val nameToUse = when {
            !displayName.isNullOrBlank() -> displayName
            !email.isNullOrBlank() -> email.substringBefore('@')
            else -> "User"
        }

        val unlinkedPerson = personDao.getDefaultPersonWithNullCloudUid()
        if (unlinkedPerson != null) {
            val updated = unlinkedPerson.copy(
                cloudUid = uid,
                email = email,
                name = if (unlinkedPerson.name == "Me" && !displayName.isNullOrBlank()) displayName else unlinkedPerson.name
            )
            personDao.updatePerson(updated)
            return updated
        }

        val newPerson = Person(
            name = nameToUse,
            isDefault = true,
            cloudUid = uid,
            email = email
        )
        val generatedId = personDao.insertPerson(newPerson)
        return newPerson.copy(id = generatedId)
    }
}
