package com.trustnet.nfc.pace

import android.util.Log
import org.bouncycastle.util.encoders.Hex

/**
 * APDU Command builder and response parser for PACE
 * Handles encoding/decoding of APDU commands and responses
 */
class PaceApduBuilder {
    companion object {
        private const val TAG = "PaceApduBuilder"
        
        /**
         * PACE Step 1: Read EF.CardAccess
         * File ID: 0x011C (EF.CardAccess location in file system)
         */
        fun selectFileCardAccess(): ByteArray {
            // SELECT BY FILE ID: 00 A4 02 0C 02 01 1C
            // CLA=00, INS=A4 (SELECT), P1=02 (file ID), P2=0C (not first/last), Lc=02, data=[01 1C]
            return byteArrayOf(
                0x00, 0xA4.toByte(), 0x02, 0x0C,  // SELECT FILE ID
                0x02,                              // Length
                0x01, 0x1C                         // EF.CardAccess ID
            )
        }
        
        /**
         * Read EF.CardAccess content
         * READ BINARY: 00 B0 00 00 XX (read all)
         */
        fun readBinary(offset: Int = 0, length: Int = 256): ByteArray {
            // READ BINARY: 00 B0 00 00 FF (read max 255 bytes)
            return byteArrayOf(
                0x00, 0xB0.toByte(),
                (offset shr 8).toByte(),     // Offset high
                offset.toByte(),             // Offset low
                length.toByte()              // Expected length
            )
        }
        
        /**
         * PACE Step 1: MSE:Set AT (Manage Security Environment)
         * Initialize PACE with parameters
         * Command: 00 22 C1 A4 0F 80 0A 04 00 7F 00 07 02 02 04 02 83 01 03
         */
        fun mseSetAt(paceOid: ByteArray = byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, 0x02)): ByteArray {
            // MSE SET: 00 22 C1 A4
            // P1=C1 (PACE), P2=A4 (AT)
            val data = byteArrayOf(0x80.toByte()) + byteArrayOf(paceOid.size.toByte()) + paceOid +  // Algorithm OID
                       byteArrayOf(0x83.toByte(), 0x01, 0x03)  // Reference control data (CAN)
            
            return byteArrayOf(0x00, 0x22, 0xC1.toByte(), 0xA4.toByte()) +
                   byteArrayOf(data.size.toByte()) +
                   data
        }
        
        /**
         * Simplified MSE:Set AT without hardcoded OID
         * Let chip use default PACE parameters from EF.CardAccess
         * Command: 00 22 C1 A4 02 83 01 03 (CAN reference only, no algorithm OID)
         */
        fun mseSetAtSimple(): ByteArray {
            // MSE SET: 00 22 C1 A4 with only CAN reference (tag 0x83)
            // This lets the chip use its own PACE parameters from EF.CardAccess
            return byteArrayOf(
                0x00, 0x22, 0xC1.toByte(), 0xA4.toByte(),  // CLA, INS, P1, P2
                0x02,                                        // Lc (length of data)
                0x83.toByte(), 0x01, 0x03                   // Tag 0x83 (CAN reference), value 0x03
            )
        }
        
        /**
         * PACE Step 1-5: General Authenticate
         * Command format: 00 86 00 00 [data] [Le]
         * CLA=00, INS=86 (GENERAL AUTHENTICATE), P1=00, P2=00
         */
        fun generalAuthenticate(commandData: ByteArray, expectedResponseLength: Int = 256): ByteArray {
            val lc = commandData.size.toByte()
            val le = expectedResponseLength.toByte()
            return byteArrayOf(0x00, 0x86.toByte(), 0x00, 0x00, lc) +
                   commandData +
                   byteArrayOf(le)
        }
        
        /**
         * Encode General Authenticate Step 1 (Get Nonce)
         * Data: 7C 0E 81 0C [encrypted nonce 16 bytes]
         */
        fun encodeGeneralAuthStep1(encryptedNonce: ByteArray): ByteArray {
            // Tag 0x7C (command data), 0x81 (encrypted nonce)
            val inner = byteArrayOf(0x81.toByte(), encryptedNonce.size.toByte()) + encryptedNonce
            return byteArrayOf(0x7C.toByte(), inner.size.toByte()) + inner
        }
        
        /**
         * Encode General Authenticate Step 2 (Mapped Generator)
         * Data: 7C 45 82 41 [public key Y 65 bytes uncompressed]
         */
        fun encodeGeneralAuthStep2(ephemeralPublicKey: ByteArray): ByteArray {
            // Tag 0x7C (command data), 0x82 (ephemeral public key)
            val inner = byteArrayOf(0x82.toByte(), ephemeralPublicKey.size.toByte()) + ephemeralPublicKey
            return byteArrayOf(0x7C.toByte(), inner.size.toByte()) + inner
        }
        
        /**
         * Encode General Authenticate Step 3 (Key Agreement)
         * Data: 7C 45 83 41 [public key Y 65 bytes uncompressed]
         */
        fun encodeGeneralAuthStep3(ephemeralPublicKey: ByteArray): ByteArray {
            // Tag 0x7C (command data), 0x83 (ephemeral public key)
            val inner = byteArrayOf(0x83.toByte(), ephemeralPublicKey.size.toByte()) + ephemeralPublicKey
            return byteArrayOf(0x7C.toByte(), inner.size.toByte()) + inner
        }
        
        /**
         * Encode General Authenticate Step 4 (Mutual Authentication)
         * Data: 7C 0A 84 08 [token 8 bytes]
         */
        fun encodeGeneralAuthStep4(token: ByteArray): ByteArray {
            // Tag 0x7C (command data), 0x84 (authentication token)
            val inner = byteArrayOf(0x84.toByte(), token.size.toByte()) + token
            return byteArrayOf(0x7C.toByte(), inner.size.toByte()) + inner
        }
        
        /**
         * Parse General Authenticate response
         * Response format varies by step:
         * - Step 1: 7C 0E 81 0C [nonce 12 bytes] (rest of 16-byte response)
         * - Step 2: 7C 45 82 41 [public key 65 bytes]
         * - Step 3: 7C 45 83 41 [public key 65 bytes]
         * - Step 4: 7C 0A 84 08 [token 8 bytes]
         */
        fun parseGeneralAuthResponse(response: ByteArray): ByteArray? {
            Log.d(TAG, "Parsing GA response: ${Hex.toHexString(response)}")
            
            if (response.size < 3) return null
            if (response[0] != 0x7C.toByte()) {
                Log.e(TAG, "Expected tag 0x7C, got ${Hex.toHexString(byteArrayOf(response[0]))}")
                return null
            }
            
            val length = response[1].toInt() and 0xFF
            if (response.size < length + 2) {
                Log.e(TAG, "Response too short: expected ${length + 2}, got ${response.size}")
                return null
            }
            
            // Skip 0x7C [length] and extract inner data (skip first tag byte)
            val data = response.drop(2).take(length).toByteArray()
            
            if (data.isEmpty()) return null
            
            val innerTag = data[0]
            val innerLength = data[1].toInt() and 0xFF
            
            if (data.size < innerLength + 2) {
                Log.e(TAG, "Inner data too short: expected ${innerLength + 2}, got ${data.size}")
                return null
            }
            
            val payload = data.drop(2).take(innerLength).toByteArray()
            Log.d(TAG, "Extracted payload tag=0x${Hex.toHexString(byteArrayOf(innerTag))}, value=${Hex.toHexString(payload)}")
            
            return payload
        }
        
        /**
         * Check if APDU response indicates success (SW=9000)
         */
        fun isApduSuccess(response: ByteArray): Boolean {
            if (response.size < 2) return false
            val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or 
                     (response[response.size - 1].toInt() and 0xFF)
            val success = sw == 0x9000
            if (!success) {
                Log.w(TAG, "APDU failed: SW=${String.format("0x%04X", sw)}")
            }
            return success
        }
        
        /**
         * Extract status word from response
         */
        fun getStatusWord(response: ByteArray): String {
            if (response.size < 2) return "INVALID"
            val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or 
                     (response[response.size - 1].toInt() and 0xFF)
            return String.format("0x%04X", sw)
        }
    }
}
