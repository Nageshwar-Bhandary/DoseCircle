package com.nagi.dosecircle.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "medications",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["personId"])
    ]
)
data class Medication(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personId: Long,
    val name: String,
    val dosage: String,
    val instructions: String,
    val photoUri: String? = null,
    val stockCount: Int = 0,
    val lowStockThreshold: Int = 0
)
