package com.trustnet.nfc.crypto

import android.util.Log
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.util.encoders.Hex
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Cryptographic utilities for PACE authentication
 * Handles ECDH, AES, CMAC operations
 */
class PaceCryptoUtils {
    companion object {
        private const val TAG = "PaceCryptoUtils"
        private const val PROVIDER = "BC"
        
        init {
            if (Security.getProvider(PROVIDER) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
        
        /**
         * Derive encryption key from CAN using MD5
         * CAN → MD5 hash → take first 16 bytes as AES-128 key
         */
        fun derivePaceKeyFromCAN(can: String): ByteArray {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(can.toByteArray(Charsets.UTF_8))
            Log.d(TAG, "CAN='$can' → Key=${Hex.toHexString(digest)}")
            return digest.copyOf(16)  // 128-bit key
        }
        
        /**
         * Decrypt nonce from chip using AES-ECB
         * Result: plain nonce (16 bytes)
         */
        fun decryptNonce(encryptedNonce: ByteArray, paceKey: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding", PROVIDER)
            val keySpec = SecretKeySpec(paceKey, 0, paceKey.size, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val plaintext = cipher.doFinal(encryptedNonce)
            Log.d(TAG, "Decrypted nonce: ${Hex.toHexString(plaintext)}")
            return plaintext
        }
        
        /**
         * Encrypt data using AES-CBC with PKCS5 padding
         * Returns: IV (16 bytes) + ciphertext
         */
        fun encryptAesCbc(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", PROVIDER)
            val keySpec = SecretKeySpec(key, 0, key.size, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            return cipher.doFinal(plaintext)
        }
        
        /**
         * Decrypt data using AES-CBC with PKCS5 padding
         */
        fun decryptAesCbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", PROVIDER)
            val keySpec = SecretKeySpec(key, 0, key.size, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            return cipher.doFinal(ciphertext)
        }
        
        /**
         * Compute AES-CMAC authentication tag
         * Used for mutual authentication in PACE step 5
         */
        fun computeAesCmac(data: ByteArray, key: ByteArray): ByteArray {
            val mac = Mac.getInstance("AESCMAC", PROVIDER)
            val keySpec = SecretKeySpec(key, 0, key.size, "AES")
            mac.init(keySpec)
            val cmac = mac.doFinal(data)
            Log.d(TAG, "CMAC(${Hex.toHexString(data)}) = ${Hex.toHexString(cmac)}")
            return cmac
        }
        
        /**
         * Generate ECDH keypair using BrainpoolP256r1 curve
         * Returns: (private key, public key bytes in uncompressed format)
         */
        fun generateEcdhKeyPair(): Pair<ECPrivateKey, ByteArray> {
            val kpg = KeyPairGenerator.getInstance("EC", PROVIDER)
            kpg.initialize(ECGenParameterSpec("brainpoolP256r1"))
            val kp = kpg.generateKeyPair()
            val privKey = kp.private as ECPrivateKey
            val pubKey = kp.public as ECPublicKey
            val pubKeyBytes = encodeEcPublicKey(pubKey)
            Log.d(TAG, "Generated ECDH keypair, public key: ${Hex.toHexString(pubKeyBytes)}")
            return Pair(privKey, pubKeyBytes)
        }
        
        /**
         * Encode EC public key to uncompressed format (0x04 || X || Y)
         * Each coordinate is 32 bytes for P-256
         */
        private fun encodeEcPublicKey(pubKey: ECPublicKey): ByteArray {
            val w = pubKey.w
            val xBytes = w.affineX.toByteArray().padStart(32)
            val yBytes = w.affineY.toByteArray().padStart(32)
            return byteArrayOf(0x04) + xBytes + yBytes
        }
        
        /**
         * Decode EC public key from uncompressed format (0x04 || X || Y)
         */
        fun decodeEcPublicKey(encodedKey: ByteArray): ECPublicKey {
            if (encodedKey[0] != 0x04.toByte()) {
                throw IllegalArgumentException("Only uncompressed format (0x04) supported")
            }
            val x = encodedKey.drop(1).take(32).toByteArray()
            val y = encodedKey.drop(33).take(32).toByteArray()
            
            val ecSpec = ECNamedCurveTable.getParameterSpec("brainpoolP256r1")
            val curveParams = ECNamedCurveSpec("brainpoolP256r1", ecSpec.curve, ecSpec.g, ecSpec.n, ecSpec.h)
            
            val xBI = java.math.BigInteger(1, x)
            val yBI = java.math.BigInteger(1, y)
            val ecPoint = ECPoint(xBI, yBI)
            
            val pubKeySpec = ECPublicKeySpec(ecPoint, curveParams)
            val keyFactory = KeyFactory.getInstance("EC", PROVIDER)
            return keyFactory.generatePublic(pubKeySpec) as ECPublicKey
        }
        
        /**
         * Perform ECDH with remote public key
         * Returns: shared secret (32 bytes)
         */
        fun performEcdh(privateKey: ECPrivateKey, remotePublicKeyBytes: ByteArray): ByteArray {
            val remotePublicKey = decodeEcPublicKey(remotePublicKeyBytes)
            
            val keyAgreement = KeyAgreement.getInstance("ECDH", PROVIDER)
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(remotePublicKey, true)
            
            val sharedSecret = keyAgreement.generateSecret()
            Log.d(TAG, "ECDH shared secret: ${Hex.toHexString(sharedSecret)}")
            return sharedSecret
        }
        
        /**
         * Derive K_enc and K_mac from shared secret using KDF
         * Returns: (K_enc: 16 bytes, K_mac: 16 bytes)
         */
        fun deriveKeysFromSharedSecret(sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
            // PACE KDF: K_enc || K_mac = SHA256(sharedSecret || 0x00 || counter)
            val md = MessageDigest.getInstance("SHA-256")
            
            // K_enc (counter = 1)
            md.update(sharedSecret)
            md.update(0x00.toByte())
            md.update(0x01.toByte())
            val kenc = md.digest().copyOf(16)
            
            // K_mac (counter = 2)
            md.reset()
            md.update(sharedSecret)
            md.update(0x00.toByte())
            md.update(0x02.toByte())
            val kmac = md.digest().copyOf(16)
            
            Log.d(TAG, "K_enc: ${Hex.toHexString(kenc)}")
            Log.d(TAG, "K_mac: ${Hex.toHexString(kmac)}")
            
            return Pair(kenc, kmac)
        }
    }
}

/**
 * Helper extension: pad ByteArray to fixed size
 */
private fun ByteArray.padStart(size: Int, paddingValue: Byte = 0.toByte()): ByteArray {
    if (this.size >= size) return this
    val padded = ByteArray(size)
    System.arraycopy(this, 0, padded, size - this.size, this.size)
    return padded
}
