package com.trustnet.app

import android.nfc.tech.IsoDep
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * PACE (Password Authenticated Connection Establishment) Implementation
 * ISO/IEC 11770-4 - Secure key exchange for government ID NFC chips
 * 
 * PACE enables mutual authentication with protected NFC files using CAN (Card Access Number)
 */
class PACEAuthenticator {
    
    companion object {
        private const val TAG = "PACEAuthenticator"
        
        // ISO/IEC 11770-4 PACE parameters
        private const val CURVE_NAME = "P-256"  // NIST P-256 / secp256r1
        private const val KEY_SIZE = 256
        
        // PACE command tags (TLV encoding)
        private const val PACE_MSE_TAG = 0xA4.toByte()
        private const val PACE_GENERAL_AUTH_TAG = 0xAA.toByte()
    }

    data class PACEResult(
        val success: Boolean,
        val sessionKey: ByteArray? = null,
        val errorMessage: String? = null
    )

    /**
     * Execute PACE authentication with the NFC chip
     * 
     * @param isoDep Connected IsoDep instance
     * @param can Card Access Number (6 characters from document)
     * @param bacKey Optional BAC key derived from MRZ data for additional security
     * @return PACEResult with success status and session key (if successful)
     */
    fun authenticate(isoDep: IsoDep, can: String, bacKey: ByteArray? = null): PACEResult {
        return try {
            Log.d(TAG, "\n=== PACE AUTHENTICATION START ===")
            Log.d(TAG, "CAN: ${if (can.isEmpty()) "EMPTY" else can}, CAN length: ${can.length}")
            Log.d(TAG, "BAC key provided: ${bacKey != null && bacKey.isNotEmpty()} (${bacKey?.size ?: 0} bytes)")
            
            // Determine authentication method
            val sharedSecret: ByteArray = when {
                can.length == 6 -> {
                    // Use CAN as shared secret
                    Log.d(TAG, "Using CAN for PACE authentication")
                    can.toByteArray()
                }
                can.isEmpty() && bacKey != null && bacKey.isNotEmpty() -> {
                    // Use BAC key directly as shared secret
                    Log.d(TAG, "CAN not provided, using BAC key (${bacKey.size} bytes) for PACE")
                    bacKey
                }
                else -> {
                    Log.e(TAG, "ERROR: No valid authentication method (CAN length: ${can.length}, BAC key present: ${bacKey != null})")
                    return PACEResult(false, null, "No valid CAN or BAC key for authentication")
                }
            }
            
            // Step 1: Initialize PACE with MSE command
            Log.d(TAG, "Step 1: Sending MSE (Manage Security Environment) command...")
            if (!sendMSE(isoDep)) {
                Log.e(TAG, "✗ MSE command failed")
                return PACEResult(false, null, "MSE initialization failed")
            }
            Log.d(TAG, "✓ MSE command succeeded")
            
            // Step 2: Perform ECDH key exchange
            Log.d(TAG, "Step 2: Performing ECDH key exchange...")
            val sessionKey = performECDH(isoDep, sharedSecret)
            if (sessionKey == null) {
                Log.e(TAG, "✗ ECDH key exchange failed")
                return PACEResult(false, null, "ECDH key exchange failed")
            }
            Log.d(TAG, "✓ ECDH key exchange succeeded (key: ${sessionKey.size} bytes)")
            
            // Step 3: Complete mutual authentication
            Log.d(TAG, "Step 3: Completing mutual authentication...")
            if (!completeMutualAuth(isoDep, sessionKey)) {
                Log.e(TAG, "✗ Mutual authentication failed")
                return PACEResult(false, null, "Mutual authentication failed")
            }
            Log.d(TAG, "✓ Mutual authentication succeeded")
            
            Log.d(TAG, "=== PACE AUTHENTICATION SUCCESSFUL ===")
            PACEResult(true, sessionKey, null)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PACE authentication: ${e.javaClass.simpleName}: ${e.message}", e)
            PACEResult(false, null, e.message ?: "Unknown error")
        }
    }

    /**
     * Send MSE (Manage Security Environment) command to initialize PACE
     * 
     * Try multiple MSE formats since Spanish DNI may use different structure
     */
    private fun sendMSE(isoDep: IsoDep): Boolean {
        return try {
            // First attempt: Standard PACE P-256 (what we just tried)
            val mseCommands = listOf(
                // Format 1: 00 22 41 A4 with TLV
                byteArrayOf(
                    0x00.toByte(), 0x22.toByte(), 0x41.toByte(), 0xA4.toByte(), 0x09.toByte(),
                    0x80.toByte(), 0x01.toByte(), 0x02.toByte(),
                    0x84.toByte(), 0x01.toByte(), 0x03.toByte(),
                    0x95.toByte(), 0x01.toByte(), 0x04.toByte()
                ),
                // Format 2: 00 22 C1 A4 with simpler TLV (just algorithm)
                byteArrayOf(
                    0x00.toByte(), 0x22.toByte(), 0xC1.toByte(), 0xA4.toByte(), 0x03.toByte(),
                    0x80.toByte(), 0x01.toByte(), 0x02.toByte()
                ),
                // Format 3: 00 22 81 A4 (alternative P1 for PACE general)
                byteArrayOf(
                    0x00.toByte(), 0x22.toByte(), 0x81.toByte(), 0xA4.toByte(), 0x09.toByte(),
                    0x80.toByte(), 0x01.toByte(), 0x02.toByte(),
                    0x84.toByte(), 0x01.toByte(), 0x03.toByte(),
                    0x95.toByte(), 0x01.toByte(), 0x04.toByte()
                ),
                // Format 4: 00 22 41 A4 with P-256 parameter value 0x20 instead of 0x04
                byteArrayOf(
                    0x00.toByte(), 0x22.toByte(), 0x41.toByte(), 0xA4.toByte(), 0x09.toByte(),
                    0x80.toByte(), 0x01.toByte(), 0x02.toByte(),
                    0x84.toByte(), 0x01.toByte(), 0x03.toByte(),
                    0x95.toByte(), 0x01.toByte(), 0x20.toByte()
                )
            )
            
            for ((index, mseCommand) in mseCommands.withIndex()) {
                Log.d(TAG, "Attempt ${index + 1}: Sending MSE: ${mseCommand.toHexString()}")
                try {
                    val response = isoDep.transceive(mseCommand)
                    val statusWord = if (response.size >= 2) {
                        String.format("%02X%02X", response[response.size-2], response[response.size-1])
                    } else "Unknown"
                    
                    Log.d(TAG, "Attempt ${index + 1} response: $statusWord (${response.size} bytes)")
                    
                    if (isSuccessResponse(response)) {
                        Log.d(TAG, "✓ MSE SUCCESS on attempt ${index + 1}")
                        return true
                    } else {
                        Log.w(TAG, "Attempt ${index + 1} failed: $statusWord - ${response.toHexString()}")
                        if (index < mseCommands.size - 1) {
                            Thread.sleep(100) // Brief delay between attempts
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Attempt ${index + 1} exception: ${e.message}")
                }
            }
            
            Log.e(TAG, "All MSE attempts failed")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendMSE: ${e.message}", e)
            false
        }
    }

    /**
     * Perform ECDH key exchange with the NFC chip
     * 
     * Exchange public keys and derive session key using ECDH
     */
    private fun performECDH(isoDep: IsoDep, sharedSecret: ByteArray): ByteArray? {
        return try {
            // Generate local ECDH key pair
            Log.d(TAG, "Generating local P-256 ECDH key pair...")
            val localKeyPair = generateECDHKeyPair()
            Log.d(TAG, "Local key pair generated")
            
            // Send our public key to chip (GENERAL AUTHENTICATE)
            Log.d(TAG, "Sending public key exchange command...")
            val chipPublicKey = sendPublicKeyExchange(isoDep, localKeyPair.public.encoded)
            if (chipPublicKey == null) {
                Log.e(TAG, "Failed to receive chip's public key")
                return null
            }
            Log.d(TAG, "Chip public key received (${chipPublicKey.size} bytes)")
            
            // Compute shared secret using ECDH
            Log.d(TAG, "Computing shared secret...")
            val ecddhSharedSecret = computeSharedSecret(localKeyPair, chipPublicKey)
            Log.d(TAG, "Shared secret computed (${ecddhSharedSecret.size} bytes)")
            
            // Derive session key from shared secret
            Log.d(TAG, "Deriving session key from shared secret...")
            val sessionKey = deriveSessionKey(ecddhSharedSecret, sharedSecret)
            Log.d(TAG, "Session key derived (${sessionKey.size} bytes)")
            
            sessionKey
        } catch (e: Exception) {
            Log.e(TAG, "Exception in performECDH: ${e.message}")
            null
        }
    }

    /**
     * Generate ECDH key pair using P-256 curve
     */
    private fun generateECDHKeyPair(): KeyPair {
        Log.d(TAG, "Generating ECDH key pair (P-256)...")
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec(CURVE_NAME))
        return keyGen.generateKeyPair()
    }

    /**
     * Send public key to chip and receive chip's public key
     */
    private fun sendPublicKeyExchange(isoDep: IsoDep, localPublicKey: ByteArray): ByteArray? {
        return try {
            // GENERAL AUTHENTICATE command: 00 AA 00 A0 ...
            // Sends our public key and requests chip's public key
            val gaCommand = ByteArray(2 + localPublicKey.size + 2)
            gaCommand[0] = 0x00.toByte()       // CLA
            gaCommand[1] = 0xAA.toByte()       // INS (General Authenticate)
            gaCommand[2] = 0x00.toByte()       // P1
            gaCommand[3] = 0xA0.toByte()       // P2
            gaCommand[4] = localPublicKey.size.toByte()  // Length
            System.arraycopy(localPublicKey, 0, gaCommand, 5, localPublicKey.size)
            
            Log.d(TAG, "Sending GENERAL AUTHENTICATE (public key exchange): ${gaCommand.toHexString()}")
            val response = isoDep.transceive(gaCommand)
            Log.d(TAG, "GA response (${response.size} bytes): ${response.toHexString()}")
            
            if (!isSuccessResponse(response)) {
                Log.w(TAG, "GA command failed with response: ${response.toHexString()}")
                return null
            }
            
            // Extract public key from response (skip status bytes)
            if (response.size > 2) {
                response.dropLast(2).toByteArray()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendPublicKeyExchange: ${e.message}")
            null
        }
    }

    /**
     * Compute shared secret using ECDH with chip's public key
     * 
     * Parse the chip's public key and perform actual ECDH key agreement
     */
    private fun computeSharedSecret(localKeyPair: KeyPair, chipPublicKeyBytes: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Computing ECDH shared secret with chip public key (${chipPublicKeyBytes.size} bytes)...")
            
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(localKeyPair.private)
            
            // Import chip's public key using X.509 SubjectPublicKeyInfo format
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val keySpec = java.security.spec.X509EncodedKeySpec(chipPublicKeyBytes)
            val chipPublicKey = keyFactory.generatePublic(keySpec)
            
            // Perform key agreement
            keyAgreement.doPhase(chipPublicKey, true)
            val sharedSecret = keyAgreement.generateSecret()
            
            Log.d(TAG, "ECDH key agreement completed: ${sharedSecret.size} bytes")
            sharedSecret
        } catch (e: Exception) {
            Log.e(TAG, "Exception in computeSharedSecret: ${e.message}")
            throw e
        }
    }

    /**
     * Derive session key from shared secret using CAN or BAC
     * 
     * Uses HKDF-SHA256 for key derivation per ISO/IEC 11770-4
     */
    private fun deriveSessionKey(sharedSecret: ByteArray, canOrBacBytes: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Deriving session key using HKDF-SHA256 (CAN/BAC: ${canOrBacBytes.size} bytes)...")
            
            // HKDF Extract phase: HMAC(salt, IKM)
            // Use empty salt and CAN/BAC as info
            val extract = javax.crypto.Mac.getInstance("HmacSHA256")
            extract.init(javax.crypto.spec.SecretKeySpec(canOrBacBytes, 0, canOrBacBytes.size, "HmacSHA256"))
            val prk = extract.doFinal(sharedSecret)  // Pseudo-random key
            
            Log.d(TAG, "HKDF-Extract: ${prk.size} bytes PRK")
            
            // HKDF Expand phase: HMAC(PRK, info || counter)
            val expand = javax.crypto.Mac.getInstance("HmacSHA256")
            expand.init(javax.crypto.spec.SecretKeySpec(prk, 0, prk.size, "HmacSHA256"))
            
            // Create info = "PACE" || 0x01 for first 32 bytes
            val info = "PACE".toByteArray() + byteArrayOf(0x01)
            expand.update(info)
            val sessionKey = expand.doFinal()
            
            Log.d(TAG, "HKDF-Expand: ${sessionKey.size} bytes session key")
            sessionKey
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deriveSessionKey: ${e.message}")
            throw e
        }
    }

    /**
     * Complete mutual authentication with chip
     */
    private fun completeMutualAuth(isoDep: IsoDep, sessionKey: ByteArray): Boolean {
        return try {
            // Send final GENERAL AUTHENTICATE with authentication token
            val authToken = sessionKey.take(8).toByteArray()  // Simplified
            
            val gaCommand = ByteArray(5 + authToken.size)
            gaCommand[0] = 0x00.toByte()       // CLA
            gaCommand[1] = 0xAA.toByte()       // INS
            gaCommand[2] = 0x00.toByte()       // P1
            gaCommand[3] = 0xA4.toByte()       // P2 (mutual auth)
            gaCommand[4] = authToken.size.toByte()
            System.arraycopy(authToken, 0, gaCommand, 5, authToken.size)
            
            Log.d(TAG, "Sending mutual auth GENERAL AUTHENTICATE...")
            val response = isoDep.transceive(gaCommand)
            Log.d(TAG, "Mutual auth response (${response.size} bytes): ${response.toHexString()}")
            
            val success = isSuccessResponse(response)
            if (!success) {
                Log.w(TAG, "Mutual auth failed with response: ${response.toHexString()}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception in completeMutualAuth: ${e.message}")
            false
        }
    }

    /**
     * Check if ISO 7816-4 response indicates success
     */
    private fun isSuccessResponse(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return (sw1 == 0x61) || (sw1 == 0x90 && sw2 == 0x00)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
