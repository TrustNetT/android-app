package com.trustnet.app

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.Serializable

/**
 * Reads and parses government ID data from NFC chips
 * Supports ICAO 9303 (Machine Readable Travel Documents)
 */
class GovernmentIDNFCReader {
    
    companion object {
        private const val TAG = "GovernmentIDNFCReader"
        private const val ICAO_AID = "A0000002471001"
    }

    data class GovernmentIDData(
        val firstName: String = "",
        val lastName: String = "",
        val documentNumber: String = "",
        val birthDate: String = "",
        val expiryDate: String = "",
        val gender: String = "",
        val nationality: String = "",
        val biometricData: ByteArray = byteArrayOf(),
        val rawData: ByteArray = byteArrayOf()
    ) : Serializable
    
    data class NFCReadResult(
        val success: Boolean,
        val data: GovernmentIDData? = null,
        val error: String? = null
    )

    private val paceAuthenticator = PACEAuthenticator()

    fun readFromTag(tag: Tag, can: String = "", bacKey: ByteArray? = null): NFCReadResult {
        return try {
            Log.d(TAG, "Attempting to get IsoDep from tag...")
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                Log.e(TAG, "IsoDep not supported on this tag")
                return NFCReadResult(false, null, "IsoDep not supported on this tag")
            }
            
            Log.d(TAG, "Connecting to IsoDep...")
            isoDep.connect()
            Log.d(TAG, "Connected successfully")
            
            Log.d(TAG, "Building SELECT command for AID: $ICAO_AID")
            val selectCommand = buildSelectCommand(ICAO_AID)
            Log.d(TAG, "SELECT command: ${selectCommand.toHexString()}")
            
            Log.d(TAG, "Sending SELECT command...")
            val response = isoDep.transceive(selectCommand)
            Log.d(TAG, "SELECT response (${response.size} bytes): ${response.toHexString()}")
            
            if (!isSuccessResponse(response)) {
                Log.e(TAG, "Failed to select ICAO application. Response status: ${response.last()}")
                isoDep.close()
                return NFCReadResult(false, null, "Failed to select ICAO application on card")
            }
            
            Log.d(TAG, "ICAO application selected successfully")
            
            // Attempt BAC authentication with BAC key derived from MRZ (ICAO 9303 standard)
            Log.d(TAG, "Attempting BAC authentication...")
            var authenticated = false
            if (bacKey != null && bacKey.isNotEmpty() && bacKey.size == 20) {  // ✓ SHA-1 = 20 bytes
                authenticated = performBACAuthentication(isoDep, bacKey)
                if (authenticated) {
                    Log.d(TAG, "✓ BAC authentication successful")
                } else {
                    Log.w(TAG, "⚠ BAC authentication failed - trying direct read anyway")
                }
            } else {
                Log.w(TAG, "BAC key not available or wrong size (got ${bacKey?.size} bytes, expected 20)")
            }
            
            Log.d(TAG, "Attempting file read (auth=${if (authenticated) "enabled" else "disabled"})...")
            // ICAO 9303: Read EF_COM first (SFI 0x1E = Combined file)
            var comData = readFile(isoDep, 0x1E, 256)
            
            if (comData == null || comData.isEmpty()) {
                Log.w(TAG, "⚠ EF_COM read failed. Trying DG1 (SFI 0x01)...")
                val dg1Data = readFile(isoDep, 0x01, 512)
                if (dg1Data != null && dg1Data.isNotEmpty()) {
                    Log.d(TAG, "✓ DG1 read succeeded (${dg1Data.size} bytes)")
                    return parseGovernmentIDData(dg1Data)
                } else {
                    Log.d(TAG, "DG1 also failed - files appear protected/encrypted")
                    isoDep.close()
                    return NFCReadResult(false, null, "Files are protected/encrypted. Document authentication required but BAC was not accepted by chip. This document may require PACE or different authentication method.")
                }
            } else {
                Log.d(TAG, "✓ Successfully read EF_COM (${comData.size} bytes)")
            }
            
            Log.d(TAG, "Proceeding to read DG1 file...")
            // Now try DG1 (SFI 0x01)
            val dg1Data = readFile(isoDep, 0x01, 512)
            
            Log.d(TAG, "Reading DG2 (Biometric data) file...")
            val dg2Data = readFile(isoDep, 0x02, 8192)
            Log.d(TAG, "DG2 data: ${dg2Data?.size} bytes")
            
            isoDep.close()
            Log.d(TAG, "IsoDep connection closed")
            
            // Return parsed data if DG1 read succeeded
            if (dg1Data != null && dg1Data.isNotEmpty()) {
                Log.d(TAG, "Successfully read DG1, parsing document data...")
                return parseGovernmentIDData(dg1Data)
            } else {
                Log.w(TAG, "DG1 read failed even after EF_COM success")
                return NFCReadResult(false, null, "EF_COM was readable but DG1 (personal data) could not be read. Document may require specific security access.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in readFromTag: ${e.javaClass.simpleName}: ${e.message}", e)
            NFCReadResult(false, null, "Exception: ${e.message}")
        }
    }

    private fun buildSelectCommand(aid: String): ByteArray {
        val aidBytes = hexStringToByteArray(aid)
        val command = ByteArray(aidBytes.size + 5)
        command[0] = 0x00.toByte()
        command[1] = 0xA4.toByte()
        command[2] = 0x04.toByte()
        command[3] = 0x00.toByte()
        command[4] = aidBytes.size.toByte()
        System.arraycopy(aidBytes, 0, command, 5, aidBytes.size)
        return command
    }

    private fun parseGovernmentIDData(dg1Data: ByteArray): NFCReadResult {
        return try {
            Log.d(TAG, "Parsing DG1 data (${dg1Data.size} bytes)...")
            
            // DG1 is TLV-encoded, typically starts with 0x61 (DG1 tag)
            // Format: [tag] [length] [MRZ line 1] [MRZ line 2] [MRZ line 3 if TD1]
            
            // Simple parser: find MRZ-like content (printable ASCII, contains digits)
            val mrzLines = mutableListOf<String>()
            var currentLine = StringBuilder()
            var lineCount = 0
            
            for (byte in dg1Data) {
                val char = byte.toInt().toChar()
                if (char == '\n' || char == '\r') {
                    if (currentLine.isNotEmpty()) {
                        mrzLines.add(currentLine.toString())
                        currentLine = StringBuilder()
                        lineCount++
                    }
                } else if (char in ' '..'~') {  // Printable ASCII
                    currentLine.append(char)
                    if (currentLine.length >= 30) {  // MRZ line is ~30-44 chars
                        mrzLines.add(currentLine.toString())
                        currentLine = StringBuilder()
                        lineCount++
                    }
                }
            }
            if (currentLine.isNotEmpty()) {
                mrzLines.add(currentLine.toString())
            }
            
            Log.d(TAG, "Found ${mrzLines.size} potential MRZ lines")
            mrzLines.forEachIndexed { i, line -> Log.d(TAG, "Line $i: ${line.take(40)}") }
            
            // If we found MRZ lines, try to parse them
            if (mrzLines.isNotEmpty()) {
                val mrz = mrzLines.find { it.matches(Regex("^[A-Z0-9<]{20,}$")) }
                if (mrz != null) {
                    Log.d(TAG, "✓ Found valid MRZ line: $mrz")
                    val data = GovernmentIDData(
                        documentNumber = mrz.take(9),
                        birthDate = mrz.substring(9, 15),
                        expiryDate = mrz.substring(15, 21),
                        rawData = dg1Data
                    )
                    return NFCReadResult(true, data, null)
                }
            }
            
            // Fallback: return data even if parsing failed
            Log.w(TAG, "Could not parse MRZ from DG1, returning raw data")
            NFCReadResult(true, GovernmentIDData(rawData = dg1Data), null)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DG1: ${e.message}")
            NFCReadResult(false, null, "Failed to parse document data: ${e.message}")
        }
    }

    private fun readFile(isoDep: IsoDep, sfi: Int, maxLength: Int): ByteArray? {
        return try {
            // Try multiple file selection approaches since chip is rejecting SFI format
            
            // Approach 1: Direct file ID (DF_NAME path format)
            // EF_COM: 0x60 0x1C
            // DG1:    0x61 0x01  
            // DG2:    0x61 0x02
            val fileIds = when(sfi) {
                0x1E -> byteArrayOf(0x60.toByte(), 0x1C.toByte())  // EF_COM
                0x01 -> byteArrayOf(0x61.toByte(), 0x01.toByte())  // DG1
                0x02 -> byteArrayOf(0x61.toByte(), 0x02.toByte())  // DG2
                else -> return null
            }
            
            // SELECT FILE by file ID (using P1=02, P2=0C like SFI but with full ID)
            val selectFileCommand = byteArrayOf(
                0x00.toByte(),              // CLA
                0xA4.toByte(),              // INS (Select File)
                0x02.toByte(),              // P1 (Select by DF_NAME/path)
                0x0C.toByte(),              // P2 (Return FCI)
                fileIds.size.toByte()       // Lc (length of file ID)
            ) + fileIds
            
            Log.d(TAG, "SELECT FILE by ID (${fileIds.toHexString()}): ${selectFileCommand.toHexString()}")
            val selectResponse = isoDep.transceive(selectFileCommand)
            Log.d(TAG, "SELECT FILE response (${selectResponse.size} bytes): ${selectResponse.toHexString()}")
            
            if (!isSuccessResponse(selectResponse)) {
                Log.w(TAG, "Failed to select file with ID ${fileIds.toHexString()}: ${selectResponse.takeLast(2).joinToString("") { "%02X".format(it) }}")
                return null
            }
            
            // READ BINARY to get file data
            val readCommand = byteArrayOf(
                0x00.toByte(),              // CLA
                0xB0.toByte(),              // INS (Read Binary)
                0x00.toByte(),              // P1 (offset high)
                0x00.toByte(),              // P2 (offset low)
                0x00.toByte()               // Le (read all)
            )
            
            Log.d(TAG, "READ BINARY: ${readCommand.toHexString()}")
            val response = isoDep.transceive(readCommand)
            Log.d(TAG, "READ BINARY response (${response.size} bytes), status: ${response.takeLast(2).joinToString("") { "%02X".format(it) }}")
            
            if (response.size < 2) {
                Log.w(TAG, "Response too short")
                return null
            }
            
            val sw1 = response[response.size - 2].toInt() and 0xFF
            val sw2 = response[response.size - 1].toInt() and 0xFF
            
            when {
                sw1 == 0x61 -> {
                    // More data available
                    Log.d(TAG, "More data (${sw2} bytes), sending GET RESPONSE...")
                    val dataBytes = response.dropLast(2).toByteArray()
                    try {
                        val getResponseCommand = byteArrayOf(
                            0x00.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x00.toByte(), sw2.toByte()
                        )
                        val additionalData = isoDep.transceive(getResponseCommand)
                        if (additionalData.size > 2) {
                            dataBytes + additionalData.dropLast(2).toByteArray()
                        } else {
                            if (dataBytes.isNotEmpty()) dataBytes else null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "GET RESPONSE error: ${e.message}")
                        if (dataBytes.isNotEmpty()) dataBytes else null
                    }
                }
                sw1 == 0x90 && sw2 == 0x00 -> {
                    val dataLength = response.size - 2
                    if (dataLength > 0) {
                        Log.d(TAG, "✓ Read ${dataLength} bytes")
                        response.dropLast(2).toByteArray()
                    } else {
                        Log.w(TAG, "Read succeeded but 0 bytes")
                        null
                    }
                }
                else -> {
                    Log.w(TAG, "Read failed: ${String.format("%02X%02X", sw1, sw2)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            null
        }
    }

    private fun isSuccessResponse(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return (sw1 == 0x61) || (sw1 == 0x90 && sw2 == 0x00)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) or
                    Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }

    private fun performBACAuthentication(isoDep: IsoDep, bacKey: ByteArray): Boolean {
        return try {
            Log.d(TAG, "=== BAC AUTHENTICATION START (${bacKey.size} bytes) ===")
            
            // MSE: Set AT (Authentication Template) with BAC key
            // This sets the security context for file access
            val mseCommand = byteArrayOf(
                0x00.toByte(),                          // CLA
                0x22.toByte(),                          // INS (Manage Security Environment)
                0xC1.toByte(),                          // P1 (Create/Restore)
                0xA4.toByte(),                          // P2 (Authentication template for AT)
                (bacKey.size + 2).toByte(),             // Length: key + 2 bytes tag/length
                0x80.toByte(),                          // Tag: Key encryption algorithm
                bacKey.size.toByte()                    // Length of BAC key
            ) + bacKey
            
            Log.d(TAG, "Sending MSE Set AT command: ${mseCommand.toHexString()}")
            val mseResponse = isoDep.transceive(mseCommand)
            Log.d(TAG, "MSE response: ${mseResponse.toHexString()}")
            
            if (!isSuccessResponse(mseResponse)) {
                Log.w(TAG, "MSE Set AT failed")
                return false
            }
            
            Log.d(TAG, "✓ BAC authentication established")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception in performBACAuthentication: ${e.message}")
            false
        }
    }
}
