package com.trustnet.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.trustnet.app.BACKeyService
import org.bouncycastle.util.encoders.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.spec.SecretKeySpec
import org.jmrtd.PassportService

/**
 * Passport reader for TD3 (passports) using BAC authentication via JMRTD
 * 
 * Implements correct JMRTD PassportService.doBAC() for TD3 passports.
 * This is the ONLY correct way to do BAC - custom MSE:Set AT breaks trust model.
 */
class PassportReaderTD3 {
    private val TAG = "PassportReaderTD3"

    suspend fun readPassportFromTag(
        tag: Tag,
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String
    ): PassportData {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "═══ TD3 PASSPORT BAC AUTHENTICATION ═══")
                Log.d(TAG, "Doc: $documentNumber | DOB: $dateOfBirth | Exp: $dateOfExpiry")
                
                // Step 1: Derive BAC key (SHA-1 of 24-char MRZ password)
                val bacService = BACKeyService()
                val bacKeySha1 = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
                
                if (bacKeySha1.size != 20) {
                    return@withContext PassportData(
                        success = false,
                        error = "BAC key wrong size: ${bacKeySha1.size} (expected 20)"
                    )
                }
                Log.d(TAG, "✓ BAC key: 20 bytes")
                
                // Step 2: Split into Kenc (0-16) and Kmac (4-20)
                val kenc = bacKeySha1.copyOfRange(0, 16)
                val kmac = bacKeySha1.copyOfRange(4, 20)
                Log.d(TAG, "✓ Keys split: Kenc[0-16] + Kmac[4-20]")
                
                // Step 3: Connect to NFC
                val isoDep = IsoDep.get(tag) ?: return@withContext PassportData(
                    success = false,
                    error = "IsoDep not available"
                )
                isoDep.connect()
                Log.d(TAG, "✓ IsoDep connected")
                
                // Step 4: Create PassportService
                val cardService = IsoDepCardServiceAdapter(isoDep)
                cardService.open()
                Log.d(TAG, "✓ CardService adapter created and opened")
                
                val passportService = PassportService(cardService, 256, 256, false, false)
                passportService.open()
                Log.d(TAG, "✓ PassportService opened")
                
                // Step 5: Perform BAC (PassportService expects SecretKey objects)
                Log.d(TAG, "→ Performing BAC...")
                val kencKey = SecretKeySpec(kenc, 0, kenc.size, "AES")
                val kmacKey = SecretKeySpec(kmac, 0, kmac.size, "AES")
                
                passportService.doBAC(kencKey, kmacKey)
                Log.d(TAG, "✓✓✓ BAC SUCCESS - Secure Messaging active ✓✓✓")
                
                isoDep.close()
                
                return@withContext PassportData(
                    documentNumber = documentNumber,
                    dateOfBirth = dateOfBirth,
                    dateOfExpiry = dateOfExpiry,
                    success = true,
                    error = ""
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "✗ BAC failed: ${e.message}")
                return@withContext PassportData(
                    success = false,
                    error = "BAC failed: ${e.message}"
                )
            }
        }
    }
}
