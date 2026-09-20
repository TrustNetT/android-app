package com.trustnetid.nfc

data class PassportData(
    val success: Boolean,
    val error: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val documentNumber: String = "",
    val dateOfBirth: String = "",
    val dateOfExpiry: String = "",
    val gender: String = "",
    val nationality: String = "",
    val faceImageBytes: ByteArray? = null
)
