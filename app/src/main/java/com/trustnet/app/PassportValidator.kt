package com.trustnet.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Passport signature validator using Android KeyStore for ECDSA operations
 */
class PassportValidator {
    
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "trustnet_passport_key"
        private const val ALGORITHM = "SHA256withECDSA"
    }

    fun validateSignature(
        documentData: ByteArray,
        signature: ByteArray,
        publicKeyBytes: ByteArray
    ): Boolean {
        return try {
            val publicKey = loadPublicKey(publicKeyBytes)
            val sig = Signature.getInstance(ALGORITHM)
            sig.initVerify(publicKey)
            sig.update(documentData)
            sig.verify(signature)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadPublicKey(publicKeyBytes: ByteArray): java.security.PublicKey {
        // Parse P-256 public key from raw bytes
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val keySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
        return keyFactory.generatePublic(keySpec)
    }

    fun generateKeyPair(): Pair<java.security.PrivateKey, java.security.PublicKey> {
        val keyPairGenerator = java.security.KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("prime256v1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        keyPairGenerator.initialize(parameterSpec)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        return Pair(keyPair.private, keyPair.public)
    }
}
