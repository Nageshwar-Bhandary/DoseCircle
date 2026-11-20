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
}
