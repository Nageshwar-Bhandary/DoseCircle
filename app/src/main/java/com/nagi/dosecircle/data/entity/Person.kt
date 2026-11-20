package com.nagi.dosecircle.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false
)
