package com.trustnetid.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
// Removed JMRTD imports - using BacAndSmSession instead for complete control
// import org.jmrtd.PassportService
// import org.jmrtd.BACKey
// import net.sf.scuba.smartcards.CommandAPDU
// import net.sf.scuba.smartcards.ResponseAPDU


class PassportReaderTD3 {

    companion object {
        private const val TAG = "PassportReaderTD3"
    }
    
    private var statusCallback: PassportReaderCallback? = null
    
    fun setStatusCallback(callback: PassportReaderCallback) {
        statusCallback = callback
    }
    
    private fun reportStatus(message: String) {
        Log.d(TAG, "[UI-STATUS] $message")
        statusCallback?.onStatus(message)
    }
    
    private fun reportError(message: String) {
        Log.e(TAG, "[UI-ERROR] $message")
        statusCallback?.onError(message)
    }

    /**
     * Read a TD3 passport using BAC (Basic Access Control).
     * 
     * Uses JMRTD's built-in BACKey class for proper key derivation per ICAO 9303.
     * DO NOT pass the 24-char password string - that's error-prone.
     * Instead, pass the individual MRZ components and let JMRTD build the password internally.
     *
     * @param tag NFC Tag (IsoDep)
     * @param documentNumber 9-character document number from MRZ line 2, positions 0-8
     * @param dateOfBirth 6-character DOB (YYMMDD) from MRZ line 2, positions 13-18
     * @param dateOfExpiry 6-character expiry (YYMMDD) from MRZ line 2, positions 21-26
     * @param bacPassword 24-char password (for logging/debugging only)
     */
    suspend fun readPassportFromTag(
        tag: Tag,
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String,
        bacPassword: String  // For logging/debugging only
    ): PassportData {
        try {
            Log.e(TAG, "█████████████████████████████████████████████████████")
            Log.e(TAG, "█ NFC_TEST_MARKER_START: TD3 PASSPORT BAC TEST BEGIN")
            Log.e(TAG, "█████████████████████████████████████████████████████")
            Log.d(TAG, "═══ TD3 PASSPORT BAC AUTHENTICATION ═══")
            Log.d(TAG, "### USING UNIFIED BacAndSmSession FLOW (Not PassportService) ###")
            
            // Log extracted MRZ fields for verification
            Log.d(TAG, "MRZ Components (extracted from OCR):")
            Log.d(TAG, "  Document Number: '$documentNumber' (expected 9 chars, got ${documentNumber.length})")
            Log.d(TAG, "  Date of Birth:   '$dateOfBirth' (expected 6 chars YYMMDD, got ${dateOfBirth.length})")
            Log.d(TAG, "  Date of Expiry:  '$dateOfExpiry' (expected 6 chars YYMMDD, got ${dateOfExpiry.length})")
            Log.d(TAG, "  Full BAC Password (for reference): '$bacPassword' (${bacPassword.length} chars)")
            
            // Validate MRZ components
            if (documentNumber.length != 9) {
                return PassportData(
                    success = false,
                    error = "Document number must be 9 characters, got ${documentNumber.length}"
                )
            }
            if (dateOfBirth.length != 6) {
                return PassportData(
                    success = false,
                    error = "Date of birth must be 6 characters (YYMMDD), got ${dateOfBirth.length}"
                )
            }
            if (dateOfExpiry.length != 6) {
                return PassportData(
                    success = false,
                    error = "Date of expiry must be 6 characters (YYMMDD), got ${dateOfExpiry.length}"
                )
            }

            // 1. Connect to IsoDep
            val isoDep = IsoDep.get(tag) ?: return PassportData(
                success = false,
                error = "IsoDep not supported on this tag"
            )

            isoDep.timeout = 5000
            isoDep.connect()
            Log.d(TAG, "✓ IsoDep connected, timeout=${isoDep.timeout}ms")

            // 2. Create unified BacAndSmSession (owns BAC + SSC + SM wrapper)
            Log.d(TAG, "→ Creating BacAndSmSession from MRZ components...")
            val session = BacAndSmSession.createFromMrz(
                isoDep,
                documentNumber,
                dateOfBirth,
                dateOfExpiry
            )
            Log.d(TAG, "✓ BacAndSmSession created")
            
            // 3. Perform BAC authentication (handles GET CHALLENGE + MUTUAL AUTHENTICATE)
            Log.d(TAG, "→ Performing BAC via BacAndSmSession.performBAC()...")
            try {
                session.performBAC()
                Log.d(TAG, "✓✓✓ BAC mutual authentication SUCCESS - SSC NOW SYNCHRONIZED")
                reportStatus("✓ BAC authentication successful")
                
                // 4. Use BacAndSmSession to read files via SM wrapper
                Log.d(TAG, "")
                Log.d(TAG, "═══ PHASE 1.5: READING PASSPORT DATA VIA SECURE MESSAGING ═══")
                reportStatus("Reading passport data via SM...")
                
                try {
                    // Step 1: Select EF.COM (list of available data groups)
                    Log.d(TAG, "→ Selecting EF.COM via SM wrapper...")
                    val comResponse = session.selectCom()
                    
                    if (comResponse.size < 2) {
                        return PassportData(
                            success = false,
                            error = "EF.COM select response too short"
                        )
                    }
                    
                    val sw = ((comResponse[comResponse.size - 2].toInt() and 0xFF) shl 8) or
                             (comResponse[comResponse.size - 1].toInt() and 0xFF)
                    Log.d(TAG, "  SELECT COM Status: 0x${sw.toString(16).padStart(4, '0')}")
                    
                    if (sw != 0x9000) {
                        Log.e(TAG, "✗ EF.COM select failed: 0x${sw.toString(16).padStart(4, '0')} - SSC may be out of sync")
                        reportStatus("✗ EF.COM select failed: 0x${sw.toString(16).padStart(4, '0')}")
                        return PassportData(
                            success = false,
                            error = "EF.COM select failed: 0x${sw.toString(16).padStart(4, '0')}"
                        )
                    }
                    
                    Log.d(TAG, "✓ EF.COM selected successfully")
                    reportStatus("✓ EF.COM selected successfully")
                    
                    // Step 2: Read EF.COM file contents
                    Log.d(TAG, "→ Reading EF.COM contents (first 100 bytes)...")
                    val comData = session.readBinary(0, 100)
                    
                    if (comData.size < 2) {
                        return PassportData(
                            success = false,
                            error = "EF.COM read response too short"
                        )
                    }
                    
                    val readSw = ((comData[comData.size - 2].toInt() and 0xFF) shl 8) or
                                 (comData[comData.size - 1].toInt() and 0xFF)
                    Log.d(TAG, "  READ BINARY Status: 0x${readSw.toString(16).padStart(4, '0')}")
                    
                    if (readSw != 0x9000) {
                        Log.e(TAG, "✗ EF.COM read failed: 0x${readSw.toString(16).padStart(4, '0')} - possible SSC desync")
                        reportStatus("✗ EF.COM read failed: 0x${readSw.toString(16).padStart(4, '0')}")
                        return PassportData(
                            success = false,
                            error = "EF.COM read failed: 0x${readSw.toString(16).padStart(4, '0')}"
                        )
                    }
                    
                    Log.d(TAG, "✓ EF.COM read successfully - ${comData.size} bytes")
                    reportStatus("✓ EF.COM read successfully (${comData.size} bytes)")
                    
                    // TODO: Phase 2 - Parse actual DG1/DG11 files
                    // For now, return success with placeholder data only when SM actually works
                    return PassportData(
                        success = true,
                        error = "",
                        firstName = "John",
                        lastName = "Doe",
                        documentNumber = documentNumber,
                        dateOfBirth = dateOfBirth,
                        dateOfExpiry = dateOfExpiry,
                        gender = "M",
                        nationality = "XX"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "✗ SM operations failed: ${e.message}", e)
                    reportError("✗ Secure Messaging error: ${e.message}")
                    return PassportData(
                        success = false,
                        error = "Secure Messaging failed: ${e.message}"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "✗ BAC failed: ${e.message}", e)
                return PassportData(
                    success = false,
                    error = "BAC failed: ${e.message}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Unexpected error in readPassportFromTag: ${e.message}", e)
            return PassportData(
                success = false,
                error = "Unexpected error: ${e.message}"
            )
        }
    }

    /**
     * Convert ByteArray to hex string (e.g., [0x12, 0xAB] → "12 AB")
     */
    private fun ByteArray.toHexString(): String {
        return this.joinToString(" ") { "%02X".format(it) }
    }
}
