package com.nagi.dosecircle.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedule_times",
    foreignKeys = [
        ForeignKey(
            entity = Schedule::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["scheduleId"])
    ]
)
data class ScheduleTime(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scheduleId: Long,
    val hour: Int,
    val minute: Int
)
