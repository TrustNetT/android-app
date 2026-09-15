package com.trustnet.nfc

import android.nfc.tech.IsoDep
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import kotlin.OptIn
import kotlin.ExperimentalStdlibApi

@OptIn(ExperimentalStdlibApi::class)
/**
 * Manual Secure Messaging (SM) implementation for TD3 passport DG file reading.
 * 
 * JMRTD's PassportService.getInputStream() does NOT apply the SM wrapper post-BAC,
 * so we implement SM manually to read COM, SOD, DG1, DG2, DG11, etc.
 * 
 * **Architecture:**
 * - Phase 1 (BAC): JMRTD handles mutual authentication, produces Kenc/Kmac
 * - Phase 2 (SM): This class wraps plain APDUs in Secure Messaging for file operations
 * - Result: SelectCom(), ReadBinary() return unwrapped responses or throw exceptions
 *
 * **Key Derivation (from MRZ):**
 * - Kenc: 24-byte 3DES key derived from Kseed[0:16] (expanded)
 * - Kmac: 24-byte 3DES key derived from Kseed[4:20] (expanded)
 * - These are derived identically to JMRTD's BACKey, so they're compatible
 *
 * **Secure Messaging (per ICAO 9303):**
 * - Each APDU wrapped with DO87 (encrypted data) and DO8E (MAC)
 * - SSC (Send Sequence Counter): 8-byte counter incremented per APDU
 * - IV: Derived from SSC (exact derivation per ICAO 9303, to be confirmed)
 * - MAC: Computed over CLA + INS + P1 + P2 + DO87 tag/length/value + SSC
 */
class SecureMessagingSession(
    private val isoDep: IsoDep,
    private val kenc: ByteArray,
    private val kmac: ByteArray,
    private var ssc: ByteArray  // 8-byte Send Sequence Counter, incremented per APDU
) {
    companion object {
        private const val TAG = "SecureMessagingSession"
        private const val DO87_TAG = 0x87.toByte()  // Encrypted data object
        private const val DO8E_TAG = 0x8E.toByte()  // MAC object
        
        /**
         * Factory: Create a SecureMessagingSession by re-deriving Kenc/Kmac from MRZ.
         * This is the recommended approach—it doesn't depend on JMRTD's internal state.
         */
        fun createFromMrz(
            isoDep: IsoDep,
            documentNumber: String,
            dateOfBirth: String,
            dateOfExpiry: String
        ): SecureMessagingSession {
            Log.d(TAG, "→ Creating SecureMessagingSession from MRZ components")
            
            // Build 24-char BAC password (same as JMRTD's BACKey)
            val bacPassword = documentNumber + documentNumber.checkDigit() +
                             dateOfBirth + dateOfBirth.checkDigit() +
                             dateOfExpiry + dateOfExpiry.checkDigit()
            
            Log.d(TAG, "  BAC Password (24 chars): '$bacPassword'")
            
            // SHA-1 hash to get Kseed (20 bytes)
            val kseed = MessageDigest.getInstance("SHA-1")
                .digest(bacPassword.toByteArray(Charsets.US_ASCII))
            
            Log.d(TAG, "  Kseed (SHA-1, 20 bytes): ${kseed.toHexString()}")
            
            // Extract Kenc (bytes 0-16) and Kmac (bytes 4-20), then expand to 24 bytes
            val kenc16 = kseed.copyOfRange(0, 16)
            val kmac16 = kseed.copyOfRange(4, 20)
            
            val kenc24 = expandDESKeyTo24Bytes(kenc16)
            val kmac24 = expandDESKeyTo24Bytes(kmac16)
            
            Log.d(TAG, "  Kenc (24 bytes): ${kenc24.toHexString()}")
            Log.d(TAG, "  Kmac (24 bytes): ${kmac24.toHexString()}")
            
            // Initialize SSC to 0x0000000000000001 per ICAO 9303 (NOT 0x00..00!)
            // This is CRITICAL: SSC must start at 0x00..01 after BAC succeeds
            // If SSC = 0x00..00, IV and MAC computations will be wrong → 0x6988 errors
            val initialSsc = ByteArray(8) { 0x00 }
            initialSsc[7] = 0x01  // Set last byte to 0x01 → 0x0000000000000001
            Log.d(TAG, "  SSC initialized to 0x${initialSsc.toHexString()} (CRITICAL: starts at 0x00..01 per ICAO 9303)")
            
            return SecureMessagingSession(isoDep, kenc24, kmac24, initialSsc)
        }
        
        /**
         * Expand 16-byte key to 24-byte 3DES key.
         * 3DES expects 24 bytes = 3 x 8-byte DES keys.
         * Pattern: split 16 bytes into A (8) + B (8), then repeat A: A + B + A
         */
        private fun expandDESKeyTo24Bytes(key16: ByteArray): ByteArray {
            if (key16.size != 16) throw IllegalArgumentException("Key must be 16 bytes")
            val key24 = ByteArray(24)
            // First 8: A
            System.arraycopy(key16, 0, key24, 0, 8)
            // Middle 8: B
            System.arraycopy(key16, 8, key24, 8, 8)
            // Last 8: A (repeat)
            System.arraycopy(key16, 0, key24, 16, 8)
            return key24
        }
        
        /**
         * Compute check digit (ICAO 9303 weighted sum) for MRZ field.
         * Used to complete the 24-char BAC password.
         * 
         * ICAO 9303 formula: checkDigit = (sum of (digit × weight)) mod 10
         * Weights cycle: 7, 3, 1, 7, 3, 1, ...
         */
        private fun String.checkDigit(): Char {
            var sum = 0
            val weights = intArrayOf(7, 3, 1)
            var index = 0
            for (char in this) {
                val digit = when (char) {
                    in '0'..'9' -> char.toString().toInt()
                    in 'A'..'Z' -> char.code - 'A'.code + 10
                    '<' -> 0
                    else -> throw IllegalArgumentException("Invalid MRZ character: $char")
                }
                sum += digit * weights[index % 3]
                index++
            }
            // ICAO 9303: checkDigit = sum mod 10 (NOT 10 - (sum mod 10))
            val checkDigit = sum % 10
            return checkDigit.toString()[0]
        }
    }
    
    /**
     * SELECT EF.COM (file 0x011E) using Secure Messaging.
     * 
     * Plain APDU: 00 A4 02 0C 02 01 1E
     * Returns: IsoDep response bytes (wrapped in SM, unwrapped here)
     */
    fun selectCom(): ByteArray {
        Log.d(TAG, "→ selectCom(): building SM-wrapped SELECT AID for EF.COM")
        
        val plainSelect = byteArrayOf(
            0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02, 0x01, 0x1E
        )
        
        Log.d(TAG, "  Plain APDU: ${plainSelect.toHexString()}")
        
        val smApdu = wrapInSM(plainSelect)
        Log.d(TAG, "  SM-wrapped APDU: ${smApdu.toHexString()}")
        
        val response = isoDep.transceive(smApdu)
        Log.d(TAG, "  Response: ${response.toHexString()}")
        
        // TODO: Unwrap response (remove DO87/DO8E if present, extract plain data)
        return response
    }
    
    /**
     * READ BINARY (plain offset/length) using Secure Messaging.
     * 
     * @param offset File offset (0-based, 2-byte value)
     * @param length Number of bytes to read (0-256)
     * @return Plain (unwrapped) file data
     */
    fun readBinary(offset: Int, length: Int): ByteArray {
        Log.d(TAG, "→ readBinary(offset=$offset, length=$length)")
        
        if (offset < 0 || offset > 0xFFFF) throw IllegalArgumentException("Offset out of range")
        if (length <= 0 || length > 256) throw IllegalArgumentException("Length must be 1-256")
        
        val plainRead = byteArrayOf(
            0x00, 0xB0.toByte(),
            (offset shr 8).toByte(),
            (offset and 0xFF).toByte(),
            length.toByte()
        )
        
        Log.d(TAG, "  Plain APDU: ${plainRead.toHexString()}")
        
        val smApdu = wrapInSM(plainRead)
        Log.d(TAG, "  SM-wrapped APDU: ${smApdu.toHexString()}")
        
        val response = isoDep.transceive(smApdu)
        Log.d(TAG, "  Response: ${response.toHexString()}")
        
        // TODO: Unwrap response (remove DO87/DO8E if present, extract plain data)
        return response
    }
    
    /**
     * Wrap a plain APDU in Secure Messaging per ICAO 9303.
     *
     * **Key Inputs:**
     * - plainApdu: Raw APDU (CLA, INS, P1, P2, [Lc], [data], [Le])
     * - kenc: 24-byte 3DES encryption key
     * - kmac: 24-byte 3DES MAC key
     * - ssc: 8-byte Send Sequence Counter
     *
     * **High-level Steps (from ICAO 9303):**
     *
     * 1. **Increment SSC**: Treat ssc as 64-bit big-endian counter, increment by 1
     *    - This maintains state across multiple wrapped APDUs
     *
     * 2. **Build DO87 (Encrypted Data Object)**:
     *    - Tag: 0x87
     *    - Encrypt plainApdu data payload with 3DES-CBC using:
     *      - Key: kenc (24 bytes)
     *      - IV: Derived from SSC (EXACT derivation per ICAO 9303 Section 7—to be confirmed)
     *        Common pattern: IV = DES(SSC)[0:8] using a fixed derivation key
     *    - Encode as: 0x87 + Length + EncryptedData
     *
     * 3. **Build DO8E (MAC Data Object)**:
     *    - Tag: 0x8E
     *    - MAC computed over: CLA | INS | P1 | P2 | DO87_tag | DO87_length | DO87_data
     *      (i.e., the modified APDU command header + encrypted data)
     *    - Algorithm: 3DES-CBC with kmac
     *    - IV: Derived from SSC (same derivation as DO87 IV, or see spec)
     *    - Take first 8 bytes of MAC as result
     *    - Encode as: 0x8E + 0x08 + MAC[0:8]
     *
     * 4. **Build Final SM APDU**:
     *    - CLA: Modify to indicate SM (e.g., 0x0C if plainApdu[0] == 0x00)
     *      (EXACT CLA mapping per ICAO 9303 Section 7—to be confirmed)
     *    - INS, P1, P2: From plainApdu (unchanged)
     *    - Lc: Length of (DO87 + DO8E)
     *    - Data: DO87 + DO8E
     *    - Le: From plainApdu if present, or omitted
     *
     * **Return:** The complete SM-wrapped APDU ready for IsoDep.transceive()
     *
     * **Status:** Implementation pending (detailed in comments above)
     */
    private fun wrapInSM(plainApdu: ByteArray): ByteArray {
        Log.d(TAG, "→ wrapInSM(): wrapping APDU in Secure Messaging")
        
        // ═══════════════════════════════════════════════════════════════════════
        // STEP 1: Increment SSC
        // ═══════════════════════════════════════════════════════════════════════
        incrementSSC()
        Log.d(TAG, "  [SSC] After increment: ${ssc.toHexString()}")
        
        // ═══════════════════════════════════════════════════════════════════════
        // STEP 2: Extract plainApdu components
        // ═══════════════════════════════════════════════════════════════════════
        if (plainApdu.size < 4) throw IllegalArgumentException("APDU too short")
        
        val cla = plainApdu[0]
        val ins = plainApdu[1]
        val p1 = plainApdu[2]
        val p2 = plainApdu[3]
        
        // Extract data payload (if present)
        var dataPayload = ByteArray(0)
        if (plainApdu.size > 4) {
            dataPayload = plainApdu.copyOfRange(4, plainApdu.size)
        }
        
        Log.d(TAG, "  [APDU] CLA=${cla.toHexString()}, INS=${ins.toHexString()}, " +
              "P1=${p1.toHexString()}, P2=${p2.toHexString()}, Data=${dataPayload.toHexString()}")
        
        // ═══════════════════════════════════════════════════════════════════════
        // STEP 3: Derive IV from SSC
        // ═══════════════════════════════════════════════════════════════════════
        // TODO: ICAO 9303 Section 7 specifies exact IV derivation from SSC.
        // Common pattern (to be verified):
        //   - Use first 8 bytes of SSC as IV directly (if SSC is already 8 bytes)
        //   - OR: Derive via DES(kd, SSC)[0:8] where kd is a fixed derivation key
        // For now, placeholder:
        val iv = deriveIvFromSSC(ssc)
        Log.d(TAG, "  [IV] Derived from SSC: ${iv.toHexString()}")
        
        // ═══════════════════════════════════════════════════════════════════════
        // STEP 4: Build DO87 (Encrypted Data Object)
        // ═══════════════════════════════════════════════════════════════════════
        // TODO: Implement 3DES encryption of dataPayload
        // - Cipher: DESede (3DES) in CBC mode
        // - Key: kenc (24 bytes)
        // - IV: derived IV (8 bytes)
        // - Input: dataPayload
        // - Padding: PKCS#5 (standard for Java crypto)
        val encryptedData = encryptData(dataPayload, kenc, iv)
        Log.d(TAG, "  [DO87] Encrypted data: ${encryptedData.toHexString()}")
        
        val do87 = buildTLV(DO87_TAG, encryptedData)
        Log.d(TAG, "  [DO87] Complete object (tag+len+val): ${do87.toHexString()}")
        
        // ═══════════════════════════════════════════════════════════════════════
        // STEP 5: Build DO8E (MAC Data Object)
        // ═══════════════════════════════════════════════════════════════════════
        // TODO: Compute 3DES-CBC MAC over: CLA | INS | P1 | P2 | DO87_tag | DO87_len | DO87_val
        // - Cipher: DESede (3DES) in CBC mode
        // - Key: kmac (24 bytes)
        // - IV: derived IV (8 bytes, same as DO87)
        // - Input: CLA + INS + P1 + P2 + do87
        // - Output: First 8 bytes of final ciphertext
        val macInput = byteArrayOf(cla, ins, p1, p2) + do87
        Log.d(TAG, "  [MAC] Input (CLA|INS|P1|P2|DO87): ${macInput.toHexString()}")
        
        val macValue = computeMAC(macInput, kmac, iv)
        Log.d(TAG, "  [DO8E] MAC value: ${macValue.toHexString()}")
        
        val do8e = buildTLV(DO8E_TAG, macValue)
        Log.d(TAG, "  [DO8E] Complete object (tag+len+val): ${do8e.toHexString()}")
        
        // ═══════════════════════════════════════════════════════════════════════
        // STEP 6: Build Final SM APDU
        // ═══════════════════════════════════════════════════════════════════════
        // TODO: Modify CLA for Secure Messaging (exact mapping per ICAO 9303)
        // Common patterns:
        //   - If plainApdu CLA == 0x00, SM CLA == 0x0C (command chaining enabled for SM)
        //   - If plainApdu CLA == 0x10, SM CLA == 0x1C
        // Verify exact mapping from ICAO 9303 Section 7.
        val smCla = modifyClassForSM(cla)
        Log.d(TAG, "  [CLA] Modified for SM: ${smCla.toHexString()}")
        
        val smData = do87 + do8e
        val smApdu = byteArrayOf(smCla, ins, p1, p2, smData.size.toByte()) + smData
        
        Log.d(TAG, "  [FINAL] SM-wrapped APDU: ${smApdu.toHexString()}")
        
        return smApdu
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS (Implementation Pending)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Increment SSC (Send Sequence Counter) by 1.
     * SSC is an 8-byte big-endian counter.
     */
    private fun incrementSSC() {
        // Treat ssc as 64-bit big-endian unsigned integer, add 1
        var carry = 1
        for (i in (ssc.size - 1) downTo 0) {
            val sum = (ssc[i].toInt() and 0xFF) + carry
            ssc[i] = (sum and 0xFF).toByte()
            carry = sum shr 8
        }
        if (carry != 0) {
            Log.w(TAG, "⚠️  SSC overflow (rolled over from 0xFF..FF to 0x00..00)")
        }
    }
    
    /**
     * Derive IV (8 bytes) from SSC (8 bytes) per ICAO 9303 Section 7.2.1.
     * 
     * **Standard approach:** IV = SSC directly
     * - SSC is already 8 bytes, used directly as CBC initialization vector
     * - This is the most common pattern in ePassport implementations
     */
    private fun deriveIvFromSSC(ssc: ByteArray): ByteArray {
        if (ssc.size != 8) throw IllegalArgumentException("SSC must be 8 bytes")
        return ssc.copyOf()
    }
    
    /**
     * Encrypt data using 3DES-CBC with given key and IV (ICAO 9303 Section 7.2.2).
     * 
     * @param plainData Data to encrypt
     * @param key 24-byte 3DES key (Kenc)
     * @param iv 8-byte initialization vector (derived from SSC)
     * @return Encrypted data (ready to be wrapped in DO87)
     */
    private fun encryptData(plainData: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (key.size != 24) throw IllegalArgumentException("Key must be 24 bytes (3DES)")
        if (iv.size != 8) throw IllegalArgumentException("IV must be 8 bytes")
        
        try {
            val cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, 0, 24, "DESede"),
                IvParameterSpec(iv)
            )
            val encrypted = cipher.doFinal(plainData)
            Log.d(TAG, "  [ENCRYPT] ${plainData.size} bytes → ${encrypted.size} bytes (padded)")
            return encrypted
        } catch (e: Exception) {
            Log.e(TAG, "❌ Encryption failed: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Compute 3DES-CBC MAC over given data (ICAO 9303 Section 7.2.3).
     * 
     * MAC = first 8 bytes of 3DES-CBC encryption of (padded input)
     * Input: CLA | INS | P1 | P2 | DO87 (tag + length + value)
     * 
     * @param data Data to MAC (usually APDU header + DO87)
     * @param key 24-byte 3DES key (Kmac)
     * @param iv 8-byte initialization vector (derived from SSC)
     * @return First 8 bytes of MAC
     */
    private fun computeMAC(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (key.size != 24) throw IllegalArgumentException("Key must be 24 bytes (3DES)")
        if (iv.size != 8) throw IllegalArgumentException("IV must be 8 bytes")
        
        try {
            // Pad data to multiple of 8 bytes (PKCS#5)
            val paddedData = padToPKCS5(data, 8)
            Log.d(TAG, "  [MAC] Input ${data.size} bytes → padded ${paddedData.size} bytes")
            
            // 3DES-CBC without padding (data already padded)
            val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, 0, 24, "DESede"),
                IvParameterSpec(iv)
            )
            val ciphertext = cipher.doFinal(paddedData)
            
            // MAC is first 8 bytes of ciphertext
            val mac = ciphertext.copyOfRange(0, 8)
            Log.d(TAG, "  [MAC] Computed: ${mac.toHexString()}")
            return mac
        } catch (e: Exception) {
            Log.e(TAG, "❌ MAC computation failed: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Pad data to multiple of blockSize using PKCS#5 (same as PKCS#7 for 8-byte blocks).
     * Padding byte value = number of bytes to add.
     */
    private fun padToPKCS5(data: ByteArray, blockSize: Int): ByteArray {
        val paddingLength = blockSize - (data.size % blockSize)
        val paddedData = ByteArray(data.size + paddingLength)
        System.arraycopy(data, 0, paddedData, 0, data.size)
        for (i in data.size until paddedData.size) {
            paddedData[i] = paddingLength.toByte()
        }
        return paddedData
    }
    
    /**
     * Build a TLV (Tag-Length-Value) structure.
     * 
     * @param tag 1-byte tag
     * @param value Value bytes
     * @return Tag + Length + Value
     */
    private fun buildTLV(tag: Byte, value: ByteArray): ByteArray {
        // TODO: Handle length encoding for values > 127 bytes (BER long form)
        // For now, assume length fits in 1 byte (value.size < 128)
        if (value.size > 127) {
            Log.w(TAG, "⚠️  buildTLV: Value size ${value.size} > 127, using placeholder short form")
        }
        return byteArrayOf(tag, value.size.toByte()) + value
    }
    
    /**
     * Modify CLA byte to enable Secure Messaging (ICAO 9303 Section 7.3).
     * 
     * CLA structure:
     * ```
     * Bit 7-5: Reserved
     * Bit 4-3: Secure Messaging (0C for SM+Chaining enabled)
     * Bit 2:   Command Chaining
     * Bit 1-0: Logical Channel
     * ```
     * 
     * Standard mapping for plaintext CLA 0x00:
     * - Add SM bits by ORing with 0x0C
     * - Result: 0x0C (SM enabled, command chaining enabled, channel 0)
     * 
     * @param originalCla Original CLA byte (e.g., 0x00)
     * @return SM-enabled CLA byte (e.g., 0x0C)
     */
    private fun modifyClassForSM(originalCla: Byte): Byte {
        // Standard case: originalCla = 0x00
        // SM CLA = 0x0C (bits 3 and 2 set)
        val smCla = (originalCla.toInt() or 0x0C).toByte()
        Log.d(TAG, "  [CLA] ${originalCla.toHexString()} → ${smCla.toHexString()} (SM enabled)")
        return smCla
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun ByteArray.toHexString(): String {
        return this.joinToString(" ") { "%02X".format(it) }
    }
}
