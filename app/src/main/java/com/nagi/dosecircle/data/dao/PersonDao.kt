package com.nagi.dosecircle.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nagi.dosecircle.data.entity.Person
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person): Long

    @Update
    suspend fun updatePerson(person: Person)

    @Query("SELECT * FROM persons")
    fun getAllPersons(): Flow<List<Person>>

    @Query("SELECT * FROM persons WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultPerson(): Person?

    @Query("SELECT * FROM persons WHERE cloudUid = :uid LIMIT 1")
    suspend fun getByCloudUid(uid: String): Person?

    @Query("SELECT * FROM persons WHERE cloudUid IS NULL LIMIT 1")
    suspend fun getDefaultPersonWithNullCloudUid(): Person?
}
