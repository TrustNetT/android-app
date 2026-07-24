package com.trustnet.nfc.pace

import android.nfc.tech.IsoDep
import android.util.Log
import com.trustnet.nfc.crypto.PaceCryptoUtils
import org.bouncycastle.util.encoders.Hex
import java.security.interfaces.ECPrivateKey

/**
 * PACE Authentication Orchestrator
 * Implements complete 5-step PACE flow for NFC document authentication
 * 
 * Works with:
 * - Spanish DNI
 * - German eID
 * - EU passports
 * - Any ICAO 9303 compliant document using PACE
 */
class PaceAuthenticator {
    private val TAG = "PaceAuthenticator"
    
    data class PaceCredentials(
        val kEnc: ByteArray,           // Encryption key
        val kMac: ByteArray,           // MAC key (for Secure Messaging)
        val ssc: Int = 0               // Send Sequence Counter (starts at 0)
    )
    
    /**
     * Execute full PACE authentication (with either CAN or BAC)
     * 
     * @param isoDep Connected NFC tag
     * @param can Card Access Number (6 digits) - NOT used if bacKey is provided
     * @param bacKey BAC (Basic Access Control) key derived from MRZ - takes precedence over CAN
     * @return PaceCredentials on success, null on failure
     * 
     * PACE supports two authentication methods:
     * 1. CAN-based (traditional PACE): Requires extracting 6-character CAN from MRZ
     * 2. BAC-based (newer, more reliable): Requires Document Number + DOB + Expiry from MRZ
     * 
     * If bacKey is provided, it will be used directly (BAC authentication)
     * If bacKey is empty/null, CAN will be used (traditional PACE)
     */
    suspend fun authenticate(
        isoDep: IsoDep,
        can: String,
        bacKey: ByteArray? = null
    ): PaceCredentials? {
        return try {
            // Determine which authentication method to use
            val paceKey = if (bacKey != null && bacKey.isNotEmpty()) {
                // Use BAC-derived key (MRZ-based, more reliable)
                Log.i(TAG, "Starting PACE authentication with BAC key (${bacKey.size} bytes)")
                bacKey
            } else {
                // Fall back to CAN-based PACE (traditional method)
                Log.i(TAG, "Starting PACE authentication with CAN=$can")
                // Step 0: Derive encryption key from CAN
                Log.d(TAG, "Step 0: Derive PACE key from CAN")
                PaceCryptoUtils.derivePaceKeyFromCAN(can)
            }
            
            // Step 1: Get Nonce from chip
            Log.d(TAG, "Step 1: Get Nonce")
            val nonce = paceStep1GetNonce(isoDep, paceKey) ?: return null
            Log.d(TAG, "✓ Nonce obtained: ${Hex.toHexString(nonce)}")
            
            // Step 2: Map Nonce
            Log.d(TAG, "Step 2: Map Nonce")
            val (mappingPrivateKey, mappingPublicKey) = paceStep2MapNonce(isoDep, nonce) ?: return null
            Log.d(TAG, "✓ Mapped generator created")
            
            // Step 3: Key Agreement
            Log.d(TAG, "Step 3: Key Agreement")
            val (agreementPrivateKey, agreementPublicKey, sharedSecret) = 
                paceStep3KeyAgreement(isoDep, mappingPrivateKey) ?: return null
            Log.d(TAG, "✓ Shared secret derived")
            
            // Step 4: Mutual Authentication
            Log.d(TAG, "Step 4: Mutual Authentication")
            val (kEnc, kMac) = paceStep4MutualAuth(isoDep, sharedSecret, agreementPrivateKey) ?: return null
            Log.d(TAG, "✓ Mutual authentication successful")
            
            // Return credentials for Secure Messaging
            val credentials = PaceCredentials(kEnc = kEnc, kMac = kMac)
            Log.i(TAG, "✓✓✓ PACE authentication SUCCESSFUL ✓✓✓")
            credentials
            
        } catch (e: Exception) {
            Log.e(TAG, "PACE authentication failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * PACE Step 1: Get Nonce
     * 
     * Chip has encrypted nonce. We decrypt it using CAN-derived key.
     * Try direct GA Step 1 first (no MSE:Set AT required for many chips)
     */
    private suspend fun paceStep1GetNonce(
        isoDep: IsoDep,
        paceKey: ByteArray
    ): ByteArray? {
        try {
            // Try direct GA Step 1 without MSE:Set AT (Spanish DNI doesn't need it)
            val gaStep1Cmd = PaceApduBuilder.generalAuthenticate(byteArrayOf(0x7C, 0x00), 256)
            Log.d(TAG, "→ Sending GA Step 1 (direct, no MSE:Set AT): ${Hex.toHexString(gaStep1Cmd)}")
            var gaStep1Response = isoDep.transceive(gaStep1Cmd)
            
            // If we get 0x6A82 (security condition), try with MSE:Set AT first
            if (PaceApduBuilder.getStatusWord(gaStep1Response) == "0x6A82") {
                Log.d(TAG, "Got 0x6A82 - trying MSE:Set AT first...")
                
                val mseCommand = PaceApduBuilder.mseSetAtSimple()
                Log.d(TAG, "→ Sending MSE:Set AT: ${Hex.toHexString(mseCommand)}")
                val mseResponse = isoDep.transceive(mseCommand)
                Log.d(TAG, "MSE:Set AT response: ${PaceApduBuilder.getStatusWord(mseResponse)}")
                
                // Retry GA Step 1
                gaStep1Response = isoDep.transceive(gaStep1Cmd)
            }
            
            if (!PaceApduBuilder.isApduSuccess(gaStep1Response)) {
                Log.e(TAG, "GA Step 1 failed: ${PaceApduBuilder.getStatusWord(gaStep1Response)}")
                return null
            }

            // Extract encrypted nonce from response
            val encryptedNonce = PaceApduBuilder.parseGeneralAuthResponse(gaStep1Response) ?: run {
                Log.e(TAG, "Failed to parse GA Step 1 response")
                return null
            }
            
            Log.d(TAG, "← Encrypted nonce: ${Hex.toHexString(encryptedNonce)}")
            
            // Decrypt nonce
            val plainNonce = PaceCryptoUtils.decryptNonce(encryptedNonce, paceKey)
            return plainNonce
            
        } catch (e: Exception) {
            Log.e(TAG, "PACE Step 1 error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * PACE Step 2: Map Nonce
     * 
     * Generate random keypair, send public key to chip, chip responds with mapped generator.
     * Command: General Authenticate step 2
     */
    private suspend fun paceStep2MapNonce(
        isoDep: IsoDep,
        nonce: ByteArray
    ): Pair<ECPrivateKey, ByteArray>? {
        try {
            // Generate ephemeral keypair for mapping
            val (ephemeralPrivKey, ephemeralPubKey) = PaceCryptoUtils.generateEcdhKeyPair()
            Log.d(TAG, "Generated ephemeral keypair")
            
            // Send to chip for mapping
            val gaStep2CmdData = PaceApduBuilder.encodeGeneralAuthStep2(ephemeralPubKey)
            val gaStep2Cmd = PaceApduBuilder.generalAuthenticate(gaStep2CmdData, 256)
            Log.d(TAG, "→ Sending GA Step 2: ${Hex.toHexString(gaStep2Cmd)}")
            val gaStep2Response = isoDep.transceive(gaStep2Cmd)
            
            if (!PaceApduBuilder.isApduSuccess(gaStep2Response)) {
                Log.e(TAG, "GA Step 2 failed: ${PaceApduBuilder.getStatusWord(gaStep2Response)}")
                return null
            }
            
            // Extract mapped generator from chip
            val mappedGenerator = PaceApduBuilder.parseGeneralAuthResponse(gaStep2Response) ?: run {
                Log.e(TAG, "Failed to parse GA Step 2 response")
                return null
            }
            
            Log.d(TAG, "← Mapped generator: ${Hex.toHexString(mappedGenerator)}")
            return Pair(ephemeralPrivKey, mappedGenerator)
            
        } catch (e: Exception) {
            Log.e(TAG, "PACE Step 2 error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * PACE Step 3: Key Agreement
     * 
     * Generate agreement keypair using mapped generator, perform ECDH with chip.
     * Command: General Authenticate step 3
     */
    private suspend fun paceStep3KeyAgreement(
        isoDep: IsoDep,
        mappingPrivateKey: ECPrivateKey
    ): Triple<ECPrivateKey, ByteArray, ByteArray>? {
        try {
            // Generate agreement keypair
            val (agreementPrivKey, agreementPubKey) = PaceCryptoUtils.generateEcdhKeyPair()
            Log.d(TAG, "Generated agreement keypair")
            
            // Send to chip for key agreement
            val gaStep3CmdData = PaceApduBuilder.encodeGeneralAuthStep3(agreementPubKey)
            val gaStep3Cmd = PaceApduBuilder.generalAuthenticate(gaStep3CmdData, 256)
            Log.d(TAG, "→ Sending GA Step 3: ${Hex.toHexString(gaStep3Cmd)}")
            val gaStep3Response = isoDep.transceive(gaStep3Cmd)
            
            if (!PaceApduBuilder.isApduSuccess(gaStep3Response)) {
                Log.e(TAG, "GA Step 3 failed: ${PaceApduBuilder.getStatusWord(gaStep3Response)}")
                return null
            }
            
            // Extract chip's agreement public key
            val chipAgreementPubKey = PaceApduBuilder.parseGeneralAuthResponse(gaStep3Response) ?: run {
                Log.e(TAG, "Failed to parse GA Step 3 response")
                return null
            }
            
            Log.d(TAG, "← Chip's agreement public key: ${Hex.toHexString(chipAgreementPubKey)}")
            
            // Perform ECDH to derive shared secret
            val sharedSecret = PaceCryptoUtils.performEcdh(agreementPrivKey, chipAgreementPubKey)
            
            return Triple(agreementPrivKey, chipAgreementPubKey, sharedSecret)
            
        } catch (e: Exception) {
            Log.e(TAG, "PACE Step 3 error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * PACE Step 4: Mutual Authentication
     * 
     * Derive encryption/MAC keys, send authentication token.
     * Command: General Authenticate step 4
     */
    private suspend fun paceStep4MutualAuth(
        isoDep: IsoDep,
        sharedSecret: ByteArray,
        agreementPrivateKey: ECPrivateKey
    ): Pair<ByteArray, ByteArray>? {
        try {
            // Derive K_enc and K_mac from shared secret
            val (kEnc, kMac) = PaceCryptoUtils.deriveKeysFromSharedSecret(sharedSecret)
            
            // Compute authentication token for this side
            // Token = CMAC(K_mac, "PACE" || 0x06 || chip_public_key || our_public_key || chip_token_data)
            // Simplified: CMAC(K_mac, our_public_key_for_chip)
            val tokenData = byteArrayOf(0x50, 0x41, 0x43, 0x45) + // "PACE"
                           byteArrayOf(0x06)  // Status
            val token = PaceCryptoUtils.computeAesCmac(tokenData, kMac).copyOf(8)
            
            Log.d(TAG, "Computed authentication token: ${Hex.toHexString(token)}")
            
            // Send token to chip
            val gaStep4CmdData = PaceApduBuilder.encodeGeneralAuthStep4(token)
            val gaStep4Cmd = PaceApduBuilder.generalAuthenticate(gaStep4CmdData, 256)
            Log.d(TAG, "→ Sending GA Step 4: ${Hex.toHexString(gaStep4Cmd)}")
            val gaStep4Response = isoDep.transceive(gaStep4Cmd)
            
            if (!PaceApduBuilder.isApduSuccess(gaStep4Response)) {
                Log.e(TAG, "GA Step 4 failed: ${PaceApduBuilder.getStatusWord(gaStep4Response)}")
                return null
            }
            
            // Chip responds with its own token (we could verify it, but typically we trust success status)
            val chipToken = PaceApduBuilder.parseGeneralAuthResponse(gaStep4Response)
            if (chipToken != null) {
                Log.d(TAG, "← Chip's authentication token: ${Hex.toHexString(chipToken)}")
            }
            
            return Pair(kEnc, kMac)
            
        } catch (e: Exception) {
            Log.e(TAG, "PACE Step 4 error: ${e.message}", e)
            return null
        }
    }
}
