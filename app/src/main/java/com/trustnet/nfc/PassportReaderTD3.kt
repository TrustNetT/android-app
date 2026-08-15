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
                
                return PassportData(
                    success = true,
                    error = ""
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
}
