package com.trustnet.app

import java.security.MessageDigest

/**
 * BAC (Basic Access Control) Key Service
 * 
 * Derives encryption keys from MRZ (Machine Readable Zone) data
 * using SHA-1 hashing as per ICAO 9303 standard
 */
class BACKeyService {
    
    companion object {
        private const val TAG = "BACKeyService"
    }
    
    /**
     * Derive BAC key from MRZ data with control digits
     * 
     * ICAO 9303 BAC Key Format (24 characters):
     * DocumentNumber(9) + DocumentChecksum(1) + DOB(6) + DOBChecksum(1) + Expiry(6) + ExpiryChecksum(1)
     * 
     * Formula:
     * 1. Use complete 24-character MRZ string including all checksums
     * 2. SHA-1 hash the complete string
     * 3. Result is Kseed (20 bytes)
     * 
     * @param bacKeyString Complete BAC key string (24 chars) with all control digits
     * @return Derived BAC key (20 bytes for SHA-1)
     */
    fun deriveBACKey(bacKeyString: String): ByteArray {
        if (bacKeyString.length != 24) {
            android.util.Log.w(TAG, "BAC key string length is ${bacKeyString.length}, expected 24. Proceeding anyway...")
        }
        
        // Hash with SHA-1 using the complete 24-character string with checksums
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val bacKey = messageDigest.digest(bacKeyString.toByteArray(Charsets.US_ASCII))
        
        android.util.Log.d(TAG, "Derived BAC key from: $bacKeyString")
        android.util.Log.d(TAG, "BAC key (20 bytes): ${bacKey.joinToString("") { "%02x".format(it) }}")
        
        // Result: 20-byte key for BAC authentication
        return bacKey
    }
    
    /**
     * Legacy method for backward compatibility
     * Deprecated: Use deriveBACKey(bacKeyString: String) instead
     */
    @Deprecated("Use deriveBACKey(String) with complete 24-char key including checksums")
    fun deriveBACKey(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String
    ): ByteArray {
        // Concatenate MRZ components exactly as per ICAO 9303
        val mrzData = documentNumber + dateOfBirth + dateOfExpiry
        
        // Hash with SHA-1
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val bacKey = messageDigest.digest(mrzData.toByteArray(Charsets.US_ASCII))
        
        // Result: 20-byte key for BAC authentication
        return bacKey
    }
    
    /**
     * Verify BAC key has correct format (20 bytes for SHA-1 hash)
     */
    fun isValidBACKey(key: ByteArray): Boolean {
        return key.size == 20
    }
}
