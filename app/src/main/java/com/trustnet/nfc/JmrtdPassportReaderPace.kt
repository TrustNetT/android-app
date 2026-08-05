package com.trustnet.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.trustnet.app.BACKeyService
import com.trustnet.app.MRZParser
import com.trustnet.nfc.pace.PaceAuthenticator
import com.trustnet.nfc.pace.PaceApduBuilder
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.bouncycastle.util.encoders.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

data class PassportData(
    val firstName: String = "",
    val lastName: String = "",
    val documentNumber: String = "",
    val dateOfBirth: String = "",
    val dateOfExpiry: String = "",
    val gender: String = "",
    val nationality: String = "",
    val faceImageBytes: ByteArray? = null,
    val success: Boolean = false,
    val error: String = ""
)

/**
 * Passport reader using BAC (Basic Access Control) authentication
 * Works with any ICAO 9303 compliant document:
 * - Spanish DNI
 * - German eID
 * - EU passports
 * - French national ID
 * - Any other government document using BAC
 * 
 * BAC requires Document Number + DOB + Expiry from MRZ
 */
class JmrtdPassportReaderPace {
    private val TAG = "JmrtdPassportReaderPace"
    private val paceAuthenticator = PaceAuthenticator()

    suspend fun readPassportFromTag(
        tag: Tag,
        mrzText: String,
        documentType: String
    ): PassportData {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "readPassportFromTag starting with MRZ text...")
                Log.d(TAG, "  MRZ Text: '$mrzText'")
                Log.d(TAG, "  Document Type: '$documentType'")
                
                // Parse MRZ to extract components with checksums
                val mrzParser = MRZParser()
                val documentNumber = mrzParser.extractDocumentNumber(mrzText, documentType)
                val dateOfBirth = mrzParser.extractDateOfBirth(mrzText, documentType)
                val dateOfExpiry = mrzParser.extractExpiryDate(mrzText, documentType)
                
                Log.d(TAG, "Extracted from MRZ:")
                Log.d(TAG, "  Document Number: '$documentNumber'")
                Log.d(TAG, "  DOB (YYMMDD): '$dateOfBirth'")
                Log.d(TAG, "  Expiry (YYMMDD): '$dateOfExpiry'")
                
                // Construct complete 24-character BAC key string with all checksums
                val bacKeyString = mrzParser.constructBACKeyString(mrzText, documentType)
                if (bacKeyString.isEmpty()) {
                    return@withContext PassportData(
                        success = false,
                        error = "Failed to construct BAC key string from MRZ"
                    )
                }
                
                if (bacKeyString.length != 24) {
                    Log.w(TAG, "⚠ BAC key string has unexpected length: ${bacKeyString.length} (expected 24)")
                }
                
                // Derive BAC key using complete 24-character string WITH checksums (ICAO 9303)
                val bacService = BACKeyService()
                val bacKey = bacService.deriveBACKey(bacKeyString)
                
                if (!bacService.isValidBACKey(bacKey)) {
                    return@withContext PassportData(
                        success = false,
                        error = "Invalid BAC key derived from MRZ"
                    )
                }
                
                Log.d(TAG, "✓ BAC key derived successfully: ${bacKey.size} bytes")
                Log.d(TAG, "  BAC Key String: $bacKeyString (length: ${bacKeyString.length})")
                Log.d(TAG, "  SHA-1 Hash: ${bacKey.joinToString("") { "%02x".format(it) }}")
                
                val isoDep = IsoDep.get(tag) ?: return@withContext PassportData(
                    success = false,
                    error = "IsoDep technology not available"
                )
                
                isoDep.connect()
                Log.d(TAG, "✓ IsoDep connected")
                
                // Select ICAO application
                val selectIcaoCmd = byteArrayOf(
                    0x00, 0xA4.toByte(), 0x04, 0x0C,  // SELECT APPLICATION
                    0x07,                              // Length
                    0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01  // ICAO OID
                )
                Log.d(TAG, "→ Selecting ICAO application")
                val selectResponse = isoDep.transceive(selectIcaoCmd)
                if (!PaceApduBuilder.isApduSuccess(selectResponse)) {
                    return@withContext PassportData(
                        success = false,
                        error = "ICAO app selection failed: ${PaceApduBuilder.getStatusWord(selectResponse)}"
                    )
                }
                Log.d(TAG, "✓ ICAO application selected")
                
                // Perform BAC authentication
                Log.d(TAG, "Starting BAC authentication...")
                val credentials = paceAuthenticator.authenticate(isoDep, "", bacKey)
                    ?: return@withContext PassportData(
                        success = false,
                        error = "BAC authentication failed - check document number, DOB, and expiry date"
                    )
                Log.d(TAG, "✓✓✓ BAC authentication successful ✓✓✓")
                
                // After BAC, files should be readable
                var firstName = ""
                var lastName = ""
                var docNum = ""
                var dob = ""
                var expiry = ""
                var gender = ""
                var nationality = ""
                var faceImageBytes: ByteArray? = null
                
                // Try to read DG1 (biographical data)
                Log.d(TAG, "Reading DG1 (biographical data)...")
                try {
                    // Select DG1 file (0x0101)
                    val selectDG1 = byteArrayOf(
                        0x00, 0xA4.toByte(), 0x02, 0x0C,  // SELECT BY FILE ID
                        0x02,                              // Length
                        0x01, 0x01                         // DG1 ID
                    )
                    Log.d(TAG, "→ Selecting DG1")
                    val selectDG1Response = isoDep.transceive(selectDG1)
                    if (PaceApduBuilder.isApduSuccess(selectDG1Response)) {
                        // Read DG1 content
                        val readDG1Cmd = PaceApduBuilder.readBinary(0, 256)
                        Log.d(TAG, "→ Reading DG1 content")
                        val dg1Content = isoDep.transceive(readDG1Cmd)
                        
                        if (PaceApduBuilder.isApduSuccess(dg1Content)) {
                            // Parse DG1File
                            val dg1Data = dg1Content.dropLast(2).toByteArray()  // Remove SW
                            Log.d(TAG, "DG1 raw data: ${Hex.toHexString(dg1Data)}")
                            
                            try {
                                val dg1File = DG1File(ByteArrayInputStream(dg1Data))
                                val mrzInfo = dg1File.mrzInfo
                                
                                if (mrzInfo != null) {
                                    firstName = mrzInfo.secondaryIdentifier.replace("<", " ").trim()
                                    lastName = mrzInfo.primaryIdentifier.replace("<", " ").trim()
                                    docNum = mrzInfo.documentNumber
                                    dob = formatDate(mrzInfo.dateOfBirth)
                                    expiry = formatDate(mrzInfo.dateOfExpiry)
                                    gender = mrzInfo.gender.toString()
                                    nationality = mrzInfo.nationality
                                    Log.d(TAG, "✓ DG1 parsed successfully: $docNum")
                                } else {
                                    Log.e(TAG, "DG1File.mrzInfo is null")
                                }
                            } catch (parseError: Exception) {
                                Log.e(TAG, "DG1 parsing error: ${parseError.message}")
                            }
                        } else {
                            Log.e(TAG, "DG1 read failed: ${PaceApduBuilder.getStatusWord(dg1Content)}")
                        }
                    } else {
                        Log.e(TAG, "DG1 select failed: ${PaceApduBuilder.getStatusWord(selectDG1Response)}")
                    }
                } catch (dg1Exception: Exception) {
                    Log.e(TAG, "DG1 error: ${dg1Exception.message}")
                }
                
                // Try to read DG2 (face image - optional)
                try {
                    Log.d(TAG, "Reading DG2 (face image)...")
                    val selectDG2 = byteArrayOf(
                        0x00, 0xA4.toByte(), 0x02, 0x0C,  // SELECT BY FILE ID
                        0x02,                              // Length
                        0x01, 0x02                         // DG2 ID
                    )
                    val selectDG2Response = isoDep.transceive(selectDG2)
                    if (PaceApduBuilder.isApduSuccess(selectDG2Response)) {
                        val readDG2Cmd = PaceApduBuilder.readBinary(0, 4096)  // DG2 can be large
                        val dg2Content = isoDep.transceive(readDG2Cmd)
                        
                        if (PaceApduBuilder.isApduSuccess(dg2Content)) {
                            try {
                                val dg2Data = dg2Content.dropLast(2).toByteArray()
                                val dg2File = DG2File(ByteArrayInputStream(dg2Data))
                                val faceImageInfoList: List<Any>? = dg2File.faceInfos
                                
                                if (faceImageInfoList != null && faceImageInfoList.isNotEmpty()) {
                                    val faceImage = faceImageInfoList[0]
                                    if (faceImage is FaceImageInfo) {
                                        faceImageBytes = faceImage.imageInputStream?.readBytes()
                                        Log.d(TAG, "✓ DG2 read: ${faceImageBytes?.size} bytes")
                                    }
                                }
                            } catch (dg2ParseError: Exception) {
                                Log.w(TAG, "DG2 parsing error: ${dg2ParseError.message}")
                            }
                        }
                    }
                } catch (dg2Exception: Exception) {
                    Log.w(TAG, "DG2 read error (optional): ${dg2Exception.message}")
                }
                
                isoDep.close()
                
                if (documentNumber.isEmpty()) {
                    return@withContext PassportData(
                        success = false,
                        error = "No document data extracted from DG1"
                    )
                }
                
                PassportData(
                    firstName = firstName,
                    lastName = lastName,
                    documentNumber = docNum,
                    dateOfBirth = dob,
                    dateOfExpiry = expiry,
                    gender = gender,
                    nationality = nationality,
                    faceImageBytes = faceImageBytes,
                    success = true,
                    error = ""
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "FATAL - Passport read failed: ${e.message}")
                e.printStackTrace()
                PassportData(
                    success = false,
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    private fun formatDate(dateStr: String): String {
        // Input: YYMMDD, Output: DD/MM/YYYY
        return if (dateStr.length == 6) {
            val yy = dateStr.substring(0, 2).toInt()
            val mm = dateStr.substring(2, 4)
            val dd = dateStr.substring(4, 6)
            val yyyy = if (yy > 50) 1900 + yy else 2000 + yy
            "$dd/$mm/$yyyy"
        } else {
            dateStr
        }
    }
}
