package com.nagi.dosecircle.data.database

import androidx.room.TypeConverter
import com.nagi.dosecircle.data.entity.DoseStatus

class Converters {
    @TypeConverter
    fun fromDoseStatus(status: DoseStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toDoseStatus(value: String?): DoseStatus? {
        return value?.let { DoseStatus.valueOf(it) }
    }
}
