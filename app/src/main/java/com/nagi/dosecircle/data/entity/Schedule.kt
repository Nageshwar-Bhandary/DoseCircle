package com.nagi.dosecircle.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedules",
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
data class Schedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personId: Long,
    val medicationId: Long,
    val startDate: Long,
    val endDate: Long? = null,
    val daysOfWeek: String, // e.g. "1,2,3,4,5,6,7"
    val isActive: Boolean = true
)
