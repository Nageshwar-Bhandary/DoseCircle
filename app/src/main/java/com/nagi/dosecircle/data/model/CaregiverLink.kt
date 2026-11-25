package com.nagi.dosecircle.data.model

import com.google.firebase.Timestamp

data class CaregiverLink(
    val id: String = "",
    val patientUid: String = "",
    val caregiverUid: String = "",
    val code: String = "",
    val status: String = "pending",
    val patientName: String? = null,
    val caregiverName: String? = null,
    val caregiverEmail: String? = null,
    val createdAt: Timestamp? = null
)
