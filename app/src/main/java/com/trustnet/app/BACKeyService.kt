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
     * Derive BAC key from complete 24-character MRZ password
     * 
     * The password is formed per ICAO 9303 as:
     * [DocNumber 9] + [DocNum Check 1] + [DOB 6 YYMMDD] + [DOB Check 1] + [Expiry 6 YYMMDD] + [Expiry Check 1]
     * = 24 characters total
     * 
     * Process:
     * 1. Accept pre-formed 24-char password
     * 2. SHA-1 hash it
     * 3. Result is Kseed (20 bytes) for JMRTD BAC authentication
     * 
     * @param bacPassword 24-character BAC password from MRZ (must be exactly 24 chars)
     * @return Derived BAC key (20 bytes for SHA-1) to pass to JMRTD PassportService.doBAC()
     */
    fun deriveBACKey(bacPassword: String): ByteArray {
        // Validate input
        if (bacPassword.length != 24) {
            throw IllegalArgumentException(
                "BAC password must be exactly 24 characters, got ${bacPassword.length}: '$bacPassword'"
            )
        }
        
        // Hash the 24-char password with SHA-1 per ICAO 9303
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val bacKey = messageDigest.digest(bacPassword.toByteArray(Charsets.US_ASCII))
        
        // Result: 20-byte key (Kseed) for BAC authentication
        return bacKey
    }
    
    /**
     * Legacy method for backward compatibility - accepts individual components
     * DEPRECATED: Use deriveBACKey(bacPassword: String) instead
     */
    @Deprecated("Use deriveBACKey(bacPassword: String) with full 24-char password")
    fun deriveBACKeyLegacy(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String
    ): ByteArray {
        // This was the old incomplete implementation - kept for reference
        val mrzData = documentNumber + dateOfBirth + dateOfExpiry
        val messageDigest = MessageDigest.getInstance("SHA-1")
        return messageDigest.digest(mrzData.toByteArray(Charsets.US_ASCII))
    }
    
    /**
     * Verify BAC key has correct format (20 bytes for SHA-1 hash)
     */
    fun isValidBACKey(key: ByteArray): Boolean {
        return key.size == 20
    }
}
