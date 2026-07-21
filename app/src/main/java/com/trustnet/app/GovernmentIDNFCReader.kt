package com.trustnet.app

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log

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
    )

    private val paceAuthenticator = PACEAuthenticator()

    fun readFromTag(tag: Tag, can: String = ""): GovernmentIDData? {
        return try {
            Log.d(TAG, "Attempting to get IsoDep from tag...")
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                Log.e(TAG, "IsoDep not supported on this tag")
                return null
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
                return null
            }
            
            Log.d(TAG, "ICAO application selected successfully")
            
            // Optional: Perform PACE authentication if CAN is provided
            if (can.isNotEmpty()) {
                Log.d(TAG, "CAN provided, attempting PACE authentication...")
                val paceResult = paceAuthenticator.authenticate(isoDep, can)
                if (paceResult.success) {
                    Log.d(TAG, "PACE authentication successful - files are now accessible")
                } else {
                    Log.w(TAG, "PACE authentication failed: ${paceResult.errorMessage}")
                    Log.w(TAG, "Continuing without authentication - file reads may fail")
                }
            } else {
                Log.d(TAG, "No CAN provided - skipping PACE authentication")
                Log.d(TAG, "File reads will fail with 6986 (Security Status Not Satisfied)")
            }
            
            Log.d(TAG, "Proceeding to read files...")
            val efComData = readFile(isoDep, byteArrayOf(0x60.toByte(), 0x1C.toByte()), 512)
            Log.d(TAG, "EF_COM data: ${efComData?.size} bytes")
            
            Log.d(TAG, "Reading DG1 (Document data) file...")
            val dg1Data = readFile(isoDep, byteArrayOf(0x61.toByte(), 0x01.toByte()), 512)
            Log.d(TAG, "DG1 data: ${dg1Data?.size} bytes")
            
            Log.d(TAG, "Reading DG2 (Biometric data) file...")
            val dg2Data = readFile(isoDep, byteArrayOf(0x61.toByte(), 0x02.toByte()), 8192)
            Log.d(TAG, "DG2 data: ${dg2Data?.size} bytes")
            
            isoDep.close()
            Log.d(TAG, "IsoDep connection closed")
            
            val governmentIDData = GovernmentIDData(
                rawData = (efComData ?: byteArrayOf()) + (dg1Data ?: byteArrayOf()),
                biometricData = dg2Data ?: byteArrayOf()
            )
            
            Log.d(TAG, "Successfully read government ID data (${governmentIDData.rawData.size} raw bytes, ${governmentIDData.biometricData.size} biometric bytes)")
            governmentIDData
        } catch (e: Exception) {
            Log.e(TAG, "Exception in readFromTag: ${e.javaClass.simpleName}: ${e.message}", e)
            null
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

    private fun readFile(isoDep: IsoDep, fileId: ByteArray, maxLength: Int): ByteArray? {
        return try {
            // READ BINARY command: 00 B0 P1 P2 Le
            // P1:P2 = offset (initially 0x0000)
            val command = byteArrayOf(
                0x00.toByte(),      // CLA
                0xB0.toByte(),      // INS (READ BINARY)
                fileId[0],          // P1
                fileId[1],          // P2
                maxLength.toByte()  // Le (length to read)
            )
            
            Log.d(TAG, "READ BINARY command for file ${String.format("%02X%02X", fileId[0], fileId[1])}: ${command.toHexString()}")
            val response = isoDep.transceive(command)
            Log.d(TAG, "READ BINARY response (${response.size} bytes): ${response.toHexString()}")
            
            if (response.size < 2) {
                Log.w(TAG, "Response too short (< 2 bytes)")
                return null
            }
            
            val sw1 = response[response.size - 2].toInt() and 0xFF
            val sw2 = response[response.size - 1].toInt() and 0xFF
            
            // 0x61 XX = More data available, 0x90 0x00 = Success
            if ((sw1 == 0x61) || (sw1 == 0x90 && sw2 == 0x00)) {
                val dataLength = response.size - 2
                Log.d(TAG, "Successfully read file: $dataLength bytes of data")
                if (dataLength > 0) {
                    response.dropLast(2).toByteArray()
                } else {
                    Log.w(TAG, "File read succeeded but returned 0 bytes")
                    null
                }
            } else {
                Log.w(TAG, "File read failed: SW1=${String.format("%02X", sw1)} SW2=${String.format("%02X", sw2)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading file: ${e.javaClass.simpleName}: ${e.message}")
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
}
