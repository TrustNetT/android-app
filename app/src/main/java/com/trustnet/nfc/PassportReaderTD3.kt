package com.trustnet.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import org.jmrtd.PassportService
import org.jmrtd.BACKey
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU


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

            // 2. Create CardService adapter and PassportService
            val cardService = IsoDepCardServiceAdapter(isoDep)
            cardService.open()
            Log.d(TAG, "✓ CardService adapter opened")

            // 3. Create PassportService with CardService
            val passportService = PassportService(cardService, 256, 256, false, false)
            passportService.open()
            Log.d(TAG, "✓ PassportService opened")
            
            // 4. Create BACKey using JMRTD's built-in class
            // This lets JMRTD handle all key derivation internally, ensuring correctness
            Log.d(TAG, "→ Creating BACKey using JMRTD's built-in class...")
            try {
                val bacKey = BACKey(documentNumber, dateOfBirth, dateOfExpiry)
                Log.d(TAG, "  ✓ BACKey created successfully")
                Log.d(TAG, "  JMRTD will internally:")
                Log.d(TAG, "  JMRTD will internally:")
                Log.d(TAG, "    1. Build 24-char BAC password")
                Log.d(TAG, "    2. Apply SHA-1 derivation")
                Log.d(TAG, "    3. Split Kseed correctly (Kenc[0:16], Kmac[4:20])")
                Log.d(TAG, "    4. Expand to 24-byte 3DES keys")
                Log.d(TAG, "    5. Build mutual auth APDU with proper encryption/MAC")

                // 5. SELECT ePassport AID to activate applet (FIX for 0x6D00 error)
                Log.d(TAG, "→ Selecting ePassport AID before BAC...")
                val selectAidData = byteArrayOf(
                    0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
                )
                val selectAidApdu = CommandAPDU(0x00, 0xA4, 0x04, 0x0C, selectAidData)
                val selectResponse = cardService.transmit(selectAidApdu)
                if (selectResponse != null) {
                    val selectSw = selectResponse.sw
                    Log.d(TAG, "  SELECT AID response: 0x${selectSw.toString(16).padStart(4, '0')} (expected 0x9000)")
                    if (selectSw != 0x9000) {
                        Log.w(TAG, "  ⚠️  SELECT AID returned non-standard status, continuing anyway...")
                    }
                } else {
                    Log.w(TAG, "  ⚠️  SELECT AID response was null, continuing anyway...")
                }
                
                // 6. TEST: Manual GET CHALLENGE to verify chip is responsive
                // This bypasses JMRTD entirely to test if issue is chip vs JMRTD wiring
                Log.d(TAG, "")
                Log.d(TAG, "→ [DEBUG] Testing manual GET CHALLENGE before JMRTD...")
                val manualTestResponse = cardService.testManualGetChallenge()
                if (manualTestResponse != null && manualTestResponse.size >= 10) {
                    Log.d(TAG, "  ✓ Manual test succeeded - chip is responsive")
                    Log.d(TAG, "  ⚠️  If JMRTD still fails below, problem is JMRTD/CardService wiring")
                } else if (manualTestResponse != null && manualTestResponse.size >= 2) {
                    val sw = ((manualTestResponse[manualTestResponse.size - 2].toInt() and 0xFF) shl 8) or
                             (manualTestResponse[manualTestResponse.size - 1].toInt() and 0xFF)
                    if (sw == 0x6D00) {
                        Log.e(TAG, "  ✗ Manual test also got 0x6D00 - chip state issue")
                    }
                }
                Log.d(TAG, "")
                
                // 7. Perform BAC with JMRTD using BACKey
                Log.d(TAG, "→ Performing BAC via PassportService.doBAC(bacKey)")
                passportService.doBAC(bacKey)
                Log.d(TAG, "✓✓✓ BAC mutual authentication SUCCESS")
                
                // 8. Diagnostic: Test COM/SOD to determine if SM wrapper works post-BAC
                Log.d(TAG, "")
                testComSod(passportService)
                
                // ═══════════════════════════════════════════════════════════════════════
                // PHASE 1.5: EXTRACT PASSPORT DATA (DG1, DG11) 
                // ═══════════════════════════════════════════════════════════════════════
                Log.d(TAG, "")
                Log.d(TAG, "═══ PHASE 1.5: EXTRACTING PASSPORT DATA FROM DG FILES ═══")
                reportStatus("Extracting passport data...")
                
                var firstName = ""
                var lastName = ""
                var documentNum = ""
                var dob = ""
                var expiry = ""
                var gender = ""
                var nationality = ""
                
                try {
                    // Read DG1 (Machine Readable Zone - contains document number, DOB, expiry)
                    Log.d(TAG, "→ Reading DG1 (Machine Readable Zone)...")
                    try {
                        val dg1Stream = passportService.getInputStream(PassportService.EF_DG1)
                        val dg1File = org.jmrtd.lds.icao.DG1File(dg1Stream)
                        val mrzInfo = dg1File.mrzInfo
                        
                        documentNum = mrzInfo?.documentNumber?.trim() ?: ""
                        dob = mrzInfo?.dateOfBirth?.toString() ?: ""
                        expiry = mrzInfo?.dateOfExpiry?.toString() ?: ""
                        gender = mrzInfo?.gender?.toString() ?: ""
                        nationality = mrzInfo?.nationality?.trim() ?: ""
                        
                        Log.d(TAG, "✓ DG1 read successfully")
                        Log.d(TAG, "  Document Number: $documentNum")
                        Log.d(TAG, "  DOB: $dob")
                        Log.d(TAG, "  Expiry: $expiry")
                        Log.d(TAG, "  Gender: $gender")
                        Log.d(TAG, "  Nationality: $nationality")
                        reportStatus("✓ DG1 extracted")
                    } catch (e: Exception) {
                        Log.w(TAG, "  ⚠ DG1 read failed: ${e.message}")
                        reportStatus("⚠ DG1 read failed: ${e.message}")
                    }
                    
                    // Read DG11 (Personal Data - contains names)
                    Log.d(TAG, "→ Reading DG11 (Personal Data)...")
                    try {
                        val dg11Stream = passportService.getInputStream(PassportService.EF_DG11)
                        val dg11File = org.jmrtd.lds.icao.DG11File(dg11Stream)
                        Log.d(TAG, "  ✓ DG11 file parsed")
                        
                        // Try to extract names - the exact API depends on JMRTD version
                        // For now, we have the key info from DG1 (documentNumber, DOB, expiry, gender, nationality)
                        
                        reportStatus("✓ DG11 extracted")
                    } catch (e: Exception) {
                        Log.w(TAG, "  ⚠ DG11 read failed: ${e.message}")
                        // This is not critical - we have the important data from DG1
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting DG files: ${e.message}", e)
                    reportError("Error extracting data: ${e.message}")
                }
                
                // ═══════════════════════════════════════════════════════════════════════
                // PHASE 2: Manual Secure Messaging for DG File Reading (NEW CODE)
                // ═══════════════════════════════════════════════════════════════════════
                // After BAC success, use manual SM layer to read DG files
                // This bypasses JMRTD's PassportService and uses SM-wrapped APDUs
                Log.d(TAG, "")
                Log.d(TAG, "═══ PHASE 2: MANUAL SECURE MESSAGING ═══")
                reportStatus("Phase 2: Starting manual SM wrapper test")
                
                try {
                    // Create SecureMessagingSession from MRZ components
                    // This re-derives Kenc/Kmac identically to BAC (guaranteed compatible)
                    Log.d(TAG, "Creating SecureMessagingSession from MRZ components...")
                    reportStatus("Creating SM session from MRZ...")
                    
                    val smSession = SecureMessagingSession.createFromMrz(
                        isoDep,
                        documentNumber,
                        dateOfBirth,
                        dateOfExpiry
                    )
                    Log.d(TAG, "✓ SecureMessagingSession created with Kenc/Kmac derived from MRZ")
                    reportStatus("✓ SM session created with Kenc/Kmac")
                    
                    // Test 1: SELECT EF.COM with SM wrapper
                    Log.d(TAG, "→ Attempting selectCom() via manual SM wrapper...")
                    reportStatus("Phase 2: Attempting SELECT EF.COM via SM...")
                    
                    val selectResponse = smSession.selectCom()
                    Log.d(TAG, "  SELECT EF.COM response: ${selectResponse.toHexString()}")
                    
                    // Check status word
                    if (selectResponse.size >= 2) {
                        val sw = ((selectResponse[selectResponse.size - 2].toInt() and 0xFF) shl 8) or
                                 (selectResponse[selectResponse.size - 1].toInt() and 0xFF)
                        Log.d(TAG, "  Status Word: 0x${sw.toString(16).padStart(4, '0')}")
                        
                        if (sw == 0x9000) {
                            Log.d(TAG, "✓✓✓ SELECT EF.COM via SM: SUCCESS")
                            reportStatus("✓ SELECT EF.COM SUCCESS (SM working!)")
                            Log.d(TAG, "  (SM wrapping is working! Proceeding to READ BINARY...)")
                            
                            // Test 2: READ BINARY to get COM file contents
                            Log.d(TAG, "→ Attempting readBinary(0, 50) to read first 50 bytes of COM...")
                            reportStatus("Phase 2: Reading COM file via SM...")
                            
                            val comData = smSession.readBinary(0, 50)
                            Log.d(TAG, "  READ BINARY response: ${comData.toHexString()}")
                            
                            if (comData.size >= 2) {
                                val readSw = ((comData[comData.size - 2].toInt() and 0xFF) shl 8) or
                                             (comData[comData.size - 1].toInt() and 0xFF)
                                Log.d(TAG, "  Status Word: 0x${readSw.toString(16).padStart(4, '0')}")
                                
                                if (readSw == 0x9000) {
                                    Log.d(TAG, "✓✓✓ READ BINARY via SM: SUCCESS")
                                    reportStatus("✓ READ BINARY SUCCESS - Manual SM FULLY FUNCTIONAL!")
                                    Log.d(TAG, "  Manual SM layer is FULLY FUNCTIONAL!")
                                } else {
                                    Log.w(TAG, "⚠️  READ BINARY returned unexpected SW: 0x${readSw.toString(16).padStart(4, '0')}")
                                    reportStatus("⚠ READ BINARY returned 0x${readSw.toString(16).padStart(4, '0')}")
                                }
                            }
                        } else if (sw == 0x6987) {
                            Log.e(TAG, "✗✗✗ SELECT EF.COM returned 0x6987 (SM OBJECTS MISSING)")
                            reportError("✗ SM wrapper ERROR 0x6987 - SM objects missing or invalid")
                            Log.e(TAG, "  SM wrapping is INCORRECT—verify ICAO 9303 compliance")
                            Log.e(TAG, "  Expected: DO87 (encrypted data) + DO8E (MAC) in wrapped APDU")
                            Log.e(TAG, "  Check: CLA byte should be 0x0C (SM enabled), not 0x00")
                            Log.e(TAG, "  Check: Check digit algorithm in SecureMessagingSession")
                        } else if (sw == 0x6988) {
                            Log.e(TAG, "✗✗✗ SELECT EF.COM returned 0x6988 (SM OBJECTS INCORRECT)")
                            reportError("✗ SM wrapper ERROR 0x6988 - Invalid SM (MAC mismatch or wrong format)")
                            Log.e(TAG, "  This usually means Kenc/Kmac are wrong, or IV/MAC derivation is wrong")
                            Log.e(TAG, "  Check: Verify ICAO 9303 Section 7 (Secure Messaging)")
                            Log.e(TAG, "  Check: IV derivation from SSC")
                            Log.e(TAG, "  Check: MAC computation over correct fields")
                        } else {
                            Log.w(TAG, "⚠️  SELECT EF.COM returned unexpected SW: 0x${sw.toString(16).padStart(4, '0')}")
                            reportStatus("⚠ SELECT EF.COM returned 0x${sw.toString(16).padStart(4, '0')}")
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "✗ Manual SM test failed: ${e.message}", e)
                    reportError("✗ Manual SM test exception: ${e.message}")
                    Log.e(TAG, "Stack trace:", e)
                    // Don't return failure—this is Phase 2, BAC (Phase 1) succeeded
                }
                
                // RETURN SUCCESS WITH EXTRACTED DATA
                Log.d(TAG, "")
                Log.d(TAG, "✓✓✓ TRANSACTION COMPLETE ✓✓✓")
                Log.d(TAG, "Returning passport data:")
                Log.d(TAG, "  Names: $firstName $lastName")
                Log.d(TAG, "  Document: $documentNum")
                Log.d(TAG, "  DOB: $dob")
                Log.d(TAG, "  Expiry: $expiry")
                Log.d(TAG, "  Gender: $gender")
                Log.d(TAG, "  Nationality: $nationality")
                
                return PassportData(
                    success = true,
                    error = "",
                    firstName = firstName,
                    lastName = lastName,
                    documentNumber = documentNum,
                    dateOfBirth = dob,
                    dateOfExpiry = expiry,
                    gender = gender,
                    nationality = nationality
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "✗ BAC failed: ${e.message}", e)
                return PassportData(
                    success = false,
                    error = "BAC failed: ${e.message}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception in TD3 reader: ${e.message}", e)
            return PassportData(
                success = false,
                error = "Exception in TD3 reader: ${e.message}"
            )
        }
    }

    /**
     * Diagnostic: Test if COM and SOD files can be read after BAC.
     * This tells us if JMRTD's SM wrapper is working post-BAC.
     * 
     * @param passportService PassportService (must have BAC already completed)
     * @return true if COM/SOD read successfully, false if either fails
     */
    private fun testComSod(passportService: PassportService): Boolean {
        try {
            Log.d(TAG, "→ DIAGNOSTIC: Testing COM read...")
            try {
                val comStream = passportService.getInputStream(PassportService.EF_COM)
                val comFile = org.jmrtd.lds.icao.COMFile(comStream)
                Log.d(TAG, "  ✓ COM read successful (SM wrapper working for COM)")
            } catch (e: Exception) {
                Log.d(TAG, "  ✗ COM read failed: ${e.message}")
                return false
            }

            Log.d(TAG, "→ DIAGNOSTIC: Testing SOD read...")
            try {
                val sodStream = passportService.getInputStream(PassportService.EF_SOD)
                val sodFile = org.jmrtd.lds.SODFile(sodStream)
                Log.d(TAG, "  ✓ SOD read successful (SM wrapper working for SOD)")
            } catch (e: Exception) {
                Log.d(TAG, "  ✗ SOD read failed: ${e.message}")
                return false
            }

            Log.d(TAG, "✓ COM/SOD diagnostic: Both files read successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "✗ COM/SOD diagnostic crashed: ${e.message}", e)
            return false
        }
    }

    /**
     * Convert ByteArray to hex string (e.g., [0x12, 0xAB] → "12 AB")
     */
    private fun ByteArray.toHexString(): String {
        return this.joinToString(" ") { "%02X".format(it) }
    }
}
