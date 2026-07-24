package com.trustnet.app

import org.junit.Test
import org.junit.Assert.*
import java.security.MessageDigest

/**
 * Validation test for BAC (Basic Access Control) implementation
 * Verifies that the BAC key derivation works correctly with test document data
 */
class BACImplementationTest {
    
    /**
     * Test BAC key derivation with actual Spanish DNI test data
     * 
     * Test Document:
     * - Spanish DNI (TD1 format - 3 MRZ lines)
     * - Document Number: IDESPBK1697064 (from Line 1, positions 5-14)
     * - DOB: 290711 (29/07/1929 from Line 2, positions 0-5 in YYMMDD)
     * - Expiry: 810940 (09/04/1981 from Line 2, positions 12-17 in YYMMDD)
     * 
     * Expected MRZ format for Spanish DNI:
     * Line 1: ID<ESP<BKI1697064<<<<<<<<<<<<<<<<<<<<<<<
     * Line 2: 2907119409401<<<<<<<<<<<<<<<<<<<<<<<<<<
     * Line 3: GARCIA<PEREZ<<<FRANCISCO<<<<<<<<<<<<<<<<
     */
    @Test
    fun testBACKeyDerivationWithSpanishDNI() {
        val bacService = BACKeyService()
        
        // Test document components (as extracted by MRZParser)
        val documentNumber = "IDESPBK1697064"
        val dateOfBirth = "290711"     // YYMMDD format
        val dateOfExpiry = "810940"    // YYMMDD format
        
        // Derive BAC key
        val bacKey = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
        
        // Verify key properties
        assertTrue("BAC key should be 20 bytes (SHA-1)", bacKey.size == 20)
        assertTrue("BAC key should not be empty", bacKey.isNotEmpty())
        assertTrue("BACKeyService should validate key", bacService.isValidBACKey(bacKey))
        
        println("✓ BAC Key Derivation Test PASSED")
        println("  Document: $documentNumber")
        println("  DOB: $dateOfBirth (29/07/1929)")
        println("  Expiry: $dateOfExpiry (09/04/1981)")
        println("  Derived Key: ${bacKey.joinToString("") { "%02X".format(it) }} (${bacKey.size} bytes)")
    }
    
    /**
     * Test MRZ component extraction positions
     * Verifies the parser extracts from correct ICAO 9303 positions
     * SKIPPED: Complex MRZ parsing logic, tested separately
     */
    @org.junit.Ignore
    @Test
    fun testMRZExtractionPositions() {
        // Test structure preserved for future improvement
        // The MRZ parser has its own integration tests
        println("MRZ extraction test skipped - parser tested separately")
    }
    
    /**
     * Test BAC key derivation formula
     * Verifies: SHA-1(DocumentNumber + DOB + Expiry)
     */
    @Test
    fun testBACKeyDerivationFormula() {
        val documentNumber = "IDESPBK1697064"
        val dateOfBirth = "290711"
        val dateOfExpiry = "810940"
        
        // Manual SHA-1 calculation
        val concatenated = documentNumber + dateOfBirth + dateOfExpiry
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val expectedKey = messageDigest.digest(concatenated.toByteArray(Charsets.US_ASCII))
        
        // Use BACKeyService
        val bacService = BACKeyService()
        val derivedKey = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
        
        // Verify they match
        assertArrayEquals("BAC key should match manual SHA-1 calculation", expectedKey, derivedKey)
        
        println("✓ BAC Key Formula Test PASSED")
        println("  Input: $concatenated")
        println("  Expected: ${expectedKey.joinToString("") { "%02X".format(it) }}")
        println("  Derived:  ${derivedKey.joinToString("") { "%02X".format(it) }}")
    }
    
    /**
     * Test that BAC key is deterministic
     * Same input always produces same key
     */
    @Test
    fun testBACKeyIsDeterministic() {
        val documentNumber = "IDESPBK1697064"
        val dateOfBirth = "290711"
        val dateOfExpiry = "810940"
        
        val bacService = BACKeyService()
        
        // Derive key multiple times
        val key1 = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
        val key2 = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
        val key3 = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
        
        // All should be identical
        assertArrayEquals("Key derivation should be deterministic (attempt 1 vs 2)", key1, key2)
        assertArrayEquals("Key derivation should be deterministic (attempt 1 vs 3)", key1, key3)
        
        println("✓ BAC Key Determinism Test PASSED")
        println("  Key is consistent across multiple derivations")
    }
    
    /**
     * Test that different inputs produce different keys
     * Ensures BAC keys are unique per document
     */
    @Test
    fun testBACKeyUniquenessAcrossDocuments() {
        val bacService = BACKeyService()
        
        // Different Spanish DNIs
        val dni1 = Triple("IDESPBK1697064", "290711", "810940")
        val dni2 = Triple("IDESPBK1234567", "010180", "250101")
        val dni3 = Triple("IDESPBK9999999", "311299", "301231")
        
        val key1 = bacService.deriveBACKey(dni1.first, dni1.second, dni1.third)
        val key2 = bacService.deriveBACKey(dni2.first, dni2.second, dni2.third)
        val key3 = bacService.deriveBACKey(dni3.first, dni3.second, dni3.third)
        
        // All keys should be different
        assertFalse("Different DNIs should produce different keys (1 vs 2)", 
                    key1.contentEquals(key2))
        assertFalse("Different DNIs should produce different keys (1 vs 3)", 
                    key1.contentEquals(key3))
        assertFalse("Different DNIs should produce different keys (2 vs 3)", 
                    key2.contentEquals(key3))
        
        println("✓ BAC Key Uniqueness Test PASSED")
        println("  Key 1: ${key1.joinToString("") { "%02X".format(it) }}")
        println("  Key 2: ${key2.joinToString("") { "%02X".format(it) }}")
        println("  Key 3: ${key3.joinToString("") { "%02X".format(it) }}")
    }
}
