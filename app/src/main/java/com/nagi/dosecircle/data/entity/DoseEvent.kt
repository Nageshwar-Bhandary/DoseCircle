package com.nagi.dosecircle.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dose_events",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Medication::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["personId"]),
        Index(value = ["medicationId"])
    ]
)
data class DoseEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personId: Long,
    val medicationId: Long,
    val scheduledTime: Long, // epoch millis
    val status: DoseStatus,
    val actionTime: Long? = null
)
