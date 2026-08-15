package com.trustnet.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import org.jmrtd.PassportService
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESedeKeySpec


class PassportReaderTD3 {

    companion object {
        private const val TAG = "PassportReaderTD3"
    }

    /**
     * Expand 16-byte key to 24-byte 3DES key (standard: append first 8 bytes)
     */
    private fun expandTo24Bytes(key16: ByteArray): ByteArray {
        if (key16.size != 16) {
            throw IllegalArgumentException("Key must be 16 bytes, got ${key16.size}")
        }
        // Standard 3DES expansion: 16 bytes + first 8 bytes = 24 bytes
        val key24 = ByteArray(24)
        System.arraycopy(key16, 0, key24, 0, 16)      // Copy first 16 bytes
        System.arraycopy(key16, 0, key24, 16, 8)      // Copy first 8 bytes at end
        return key24
    }

    /**
     * Read a TD3 passport using BAC.
     *
     * @param tag NFC Tag (IsoDep)
     * @param bacPassword 24-char MRZ password
     */
    suspend fun readPassportFromTag(tag: Tag, bacPassword: String): PassportData {
        try {
            Log.d(TAG, "═══ TD3 PASSPORT BAC AUTHENTICATION ═══")
            Log.d(TAG, "BAC password: '$bacPassword' (length=${bacPassword.length})")

            if (bacPassword.length != 24) {
                return PassportData(
                    success = false,
                    error = "BAC password must be 24 characters, got ${bacPassword.length}"
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
            
            // 4. Derive BAC keys from 24-char password
            val kseed = MessageDigest.getInstance("SHA-1")
                .digest(bacPassword.toByteArray(Charsets.US_ASCII))

            if (kseed.size != 20) {
                return PassportData(
                    success = false,
                    error = "Kseed must be 20 bytes, got ${kseed.size}"
                )
            }

            val kenc = kseed.copyOfRange(0, 16)   // 0..15
            val kmac = kseed.copyOfRange(4, 20)   // 4..19

            Log.d(TAG, "✓ Kseed: ${kseed.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "✓ Kenc:  ${kenc.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "✓ Kmac:  ${kmac.joinToString("") { "%02x".format(it) }}")

            // 5. Build 3DES SecretKey objects from 16-byte keys
            // JMRTD 0.7.33 requires SecretKey, not raw byte arrays
            // Expand to 24-byte 3DES keys (standard: 16 bytes + first 8 bytes)
            try {
                Log.d(TAG, "→ Building 3DES SecretKey objects for BAC...")
                
                val kenc24 = expandTo24Bytes(kenc)
                val kmac24 = expandTo24Bytes(kmac)
                Log.d(TAG, "  Kenc expanded to 24 bytes")
                Log.d(TAG, "  Kmac expanded to 24 bytes")
                
                val keyFactory = SecretKeyFactory.getInstance("DESede")
                val kencKey = keyFactory.generateSecret(DESedeKeySpec(kenc24, 0))
                val kmacKey = keyFactory.generateSecret(DESedeKeySpec(kmac24, 0))
                Log.d(TAG, "  ✓ 3DES SecretKey objects created")

                // 6. SELECT ePassport AID to activate applet (FIX for 0x6D00 error)
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
                
                // 7. Perform BAC with JMRTD 0.7.33 API
                Log.d(TAG, "→ Performing BAC via PassportService.doBAC(kencKey, kmacKey)")
                passportService.doBAC(kencKey, kmacKey)
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
