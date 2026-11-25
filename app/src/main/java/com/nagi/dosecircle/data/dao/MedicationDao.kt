package com.nagi.dosecircle.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nagi.dosecircle.data.entity.Medication
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: Medication): Long

    @Update
    suspend fun updateMedication(medication: Medication)

    @Delete
    suspend fun deleteMedication(medication: Medication)

    @Query("SELECT * FROM medications WHERE personId = :personId")
    fun getAllMedicationsForPerson(personId: Long): Flow<List<Medication>>

    @Query("SELECT * FROM medications WHERE id = :id LIMIT 1")
    suspend fun getMedicationById(id: Long): Medication?

    @Query("SELECT * FROM medications WHERE personId = :personId AND cloudId = :cloudId LIMIT 1")
    suspend fun getMedicationByCloudId(personId: Long, cloudId: String): Medication?

    @Query("UPDATE medications SET stockCount = MAX(stockCount - :amount, 0) WHERE id = :id")
    suspend fun decreaseStock(id: Long, amount: Int = 1)

    @Query("UPDATE medications SET stockCount = stockCount + :amount WHERE id = :id")
    suspend fun increaseStock(id: Long, amount: Int)
}
