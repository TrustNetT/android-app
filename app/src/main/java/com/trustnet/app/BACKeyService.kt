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
     * Derive BAC key from MRZ data
     * 
     * Formula:
     * 1. Concatenate: documentNumber + dateOfBirth (YYMMDD) + dateOfExpiry (YYMMDD)
     * 2. SHA-1 hash the concatenated string
     * 3. Result is Kseed (20 bytes)
     * 4. Derive Kenc and Kmac from Kseed (implementation specific)
     * 
     * @param documentNumber Document number from MRZ (e.g., "IDESPBK1169706")
     * @param dateOfBirth Date of birth in YYMMDD format (e.g., "290711")
     * @param dateOfExpiry Expiry date in YYMMDD format (e.g., "810940")
     * @return Derived BAC key (20 bytes for SHA-1)
     */
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
