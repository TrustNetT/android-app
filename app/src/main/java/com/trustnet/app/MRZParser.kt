package com.trustnet.app

import android.util.Log

/**
 * Parser for Machine Readable Zone (MRZ) text extracted from documents
 * Extracts CAN (Card Access Number) from MRZ based on document type
 */
class MRZParser {
    
    companion object {
        private const val TAG = "MRZParser"
    }
    
    /**
     * Extract actual MRZ lines from full OCR text
     * MRZ lines are at the bottom of the document and contain only:
     * - Letters, numbers, '<', and '<' characters
     * - Typically 2 lines for TD3 (Passport), 3 lines for TD1 (ID Card)
     * - Each line is 44 characters (TD3) or 30 characters (TD1)
     * 
     * This is critical because OCR extracts ALL text on the document,
     * but we only want the machine-readable zone
     */
    fun extractMRZLines(fullOCRText: String): String {
        val allLines = fullOCRText.trim().split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        Log.d(TAG, "extractMRZLines: Found ${allLines.size} total lines in OCR")
        for (i in allLines.indices) {
            Log.d(TAG, "  Line $i: '${allLines[i].take(50)}...' (length: ${allLines[i].length})")
        }
        
        // MRZ lines consist of ONLY alphanumeric + '<' characters (NO other chars)
        // Pattern 1: Normal MRZ (30+ chars, A-Z, 0-9, <) - must be mostly < and alphanumeric
        val mrzPattern = Regex("^[A-Z0-9<]{20,}$")
        
        // Find consecutive lines that match MRZ pattern
        // The actual MRZ will have many < characters and be at least 30 chars long
        val mrzLines = mutableListOf<String>()
        var inMRZBlock = false
        
        for (i in allLines.indices.reversed()) { // Start from bottom (where MRZ should be)
            val line = allLines[i]
            // Clean OCR errors: replace common confusions but preserve actual 0s and 1s in MRZ
            val cleanedLine = line.replace(" ", "")  // Remove spaces only
                                   .replace("O", "0") // WRONG if already 0, but OCR tends to read 0 as O
            
            // MRZ lines should be mostly < characters and digits/letters, with length 20+
            val mrzCharCount = cleanedLine.count { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
            val isMRZLine = mrzCharCount >= 18 && cleanedLine.length >= 20 && cleanedLine.contains('<')
            
            Log.d(TAG, "  Line $i evaluation: original='$line', cleaned='$cleanedLine', isMRZ=$isMRZLine (mrzCharCount=$mrzCharCount)")
            
            if (isMRZLine) {
                mrzLines.add(0, cleanedLine) // Insert at beginning to maintain order
                inMRZBlock = true
                Log.d(TAG, "    ✓ MRZ line found")
            } else if (inMRZBlock) {
                // We were in MRZ block but found a non-MRZ line
                Log.d(TAG, "    ✗ End of MRZ block")
                break
            }
        }
        
        Log.d(TAG, "extractMRZLines: Found ${mrzLines.size} MRZ lines")
        if (mrzLines.isNotEmpty()) {
            mrzLines.forEach { Log.d(TAG, "  MRZ: '$it'") }
        }
        
        // If we found MRZ lines, use them; otherwise return empty (will trigger re-capture)
        val result = if (mrzLines.size >= 2) {
            mrzLines.joinToString("\n")
        } else {
            Log.w(TAG, "No valid MRZ lines found in OCR text (found ${mrzLines.size}, need >= 2)")
            ""
        }
        
        Log.d(TAG, "Final MRZ text:\n$result")
        return result
    }
    
    /**
     * Extract CAN (Card Access Number) from MRZ text
     * 
     * CAN locations by document type (ICAO 9303):
     * - TD3 (Passport): Line 2, positions 15-20 (characters 15-20, 0-indexed)
     * - TD1 (ID Card): Line 3, positions 5-10 (characters 5-10, 0-indexed)
     * - TD2 (Visa): Line 2, positions 15-20
     * 
     * CAN format: 6 alphanumeric characters
     */
    fun extractCAN(mrzText: String, documentType: String): String {
        Log.d(TAG, "Extracting CAN from document type: $documentType")
        
        // Normalize the MRZ text - remove extra whitespace and split into lines
        val lines = mrzText.trim().split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        Log.d(TAG, "MRZ has ${lines.size} lines")
        
        return when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                // TD3 Format (Passport): CAN is in line 2 (index 1), positions 15-20
                extractFromTD3(lines)
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                // TD1 Format (ID Card): CAN is in line 3 (index 2), positions 5-10
                extractFromTD1(lines)
            }
            else -> {
                // Try both formats
                val td3Result = extractFromTD3(lines)
                if (td3Result.isNotEmpty()) td3Result else extractFromTD1(lines)
            }
        }
    }
    
    /**
     * Extract CAN from TD3 (Passport) format
     * Line 2: Position 15-20 contains CAN
     * Expected format: Line 2 = "P<ISOCODE..."
     */
    private fun extractFromTD3(lines: List<String>): String {
        if (lines.size < 2) {
            Log.w(TAG, "Not enough lines for TD3 format. Have ${lines.size}, need 2")
            return ""
        }
        
        val line2 = lines[1]
        Log.d(TAG, "TD3 Line 2: $line2 (length: ${line2.length})")
        
        // CAN is at positions 15-20 (0-indexed: 15-21)
        return try {
            if (line2.length >= 21) {
                val can = line2.substring(15, 21)
                Log.d(TAG, "Extracted CAN from TD3: $can")
                can
            } else {
                Log.w(TAG, "Line 2 too short for CAN extraction")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CAN from TD3: ${e.message}")
            ""
        }
    }
    
    /**
     * Extract CAN from TD1 (ID Card) format
     * Line 3: Position 5-10 contains CAN
     * Expected format: Line 3 = "...5-digit check digit..."
     */
    private fun extractFromTD1(lines: List<String>): String {
        if (lines.size < 3) {
            Log.w(TAG, "Not enough lines for TD1 format. Have ${lines.size}, need 3")
            return ""
        }
        
        val line3 = lines[2]
        Log.d(TAG, "TD1 Line 3: $line3 (length: ${line3.length})")
        
        // CAN is at positions 5-10 (0-indexed: 5-11)
        return try {
            if (line3.length >= 11) {
                val can = line3.substring(5, 11)
                Log.d(TAG, "Extracted CAN from TD1: $can")
                can
            } else {
                Log.w(TAG, "Line 3 too short for CAN extraction")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CAN from TD1: ${e.message}")
            ""
        }
    }
    
    /**
     * Validate CAN format (6 alphanumeric characters)
     */
    fun isValidCAN(can: String): Boolean {
        return can.length == 6 && can.all { it.isLetterOrDigit() }
    }
    
    /**
     * Extract document number from MRZ
     * TD3 (Passport) Line 2: ICAO 9303 Positions 0-8 (9 digits) = substring(0, 9)
     * TD1 (ID Card) Line 1: ICAO 9303 Positions 5-13 (9 digits) = substring(5, 14)
     * NOTE: MRZ lines never contain spaces - remove all spaces first (OCR artifacts)
     */
    fun extractDocumentNumber(mrzText: String, documentType: String): String {
        val lines = mrzText.trim().split("\n")
            .map { it.trim().replace(" ", "") }  // Remove ALL spaces - MRZ never has spaces
            .filter { it.isNotEmpty() }
        
        Log.d(TAG, "extractDocumentNumber: type=$documentType, lines=${lines.size}")
        for (i in lines.indices) {
            Log.d(TAG, "  Line $i (no spaces): '${lines[i]}' (length: ${lines[i].length})")
        }
        
        val result = when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                Log.d(TAG, "Using TD3 format for document number")
                if (lines.size >= 2 && lines[1].length >= 9) {
                    lines[1].substring(0, 9).trim()
                } else {
                    Log.w(TAG, "TD3: Not enough data (have ${lines.size} lines, line[1] length=${if (lines.size >= 2) lines[1].length else 0})")
                    ""
                }
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                Log.d(TAG, "Using TD1 format for document number")
                if (lines.size >= 1 && lines[0].length >= 14) {
                    lines[0].substring(5, 14).trim()
                } else {
                    Log.w(TAG, "TD1: Not enough data (have ${lines.size} lines, line[0] length=${if (lines.size >= 1) lines[0].length else 0})")
                    ""
                }
            }
            else -> {
                Log.w(TAG, "Unknown document type: $documentType")
                ""
            }
        }
        Log.d(TAG, "extractDocumentNumber result: '$result'")
        return result
    }
    
    /**
     * Extract date of birth from MRZ
     * TD3 (Passport) Line 2: ICAO 9303 Positions 13-18 (YYMMDD) = substring(13, 19)
     * TD1 (ID Card) Line 2: ICAO 9303 Positions 0-5 (YYMMDD) = substring(0, 6)
     * NOTE: MRZ lines never contain spaces - remove all spaces first (OCR artifacts)
     */
    fun extractDateOfBirth(mrzText: String, documentType: String): String {
        val lines = mrzText.trim().split("\n")
            .map { it.trim().replace(" ", "") }  // Remove ALL spaces - MRZ never has spaces
            .filter { it.isNotEmpty() }
        
        Log.d(TAG, "extractDateOfBirth: type=$documentType, lines=${lines.size}")
        for (i in lines.indices) {
            Log.d(TAG, "  Line $i (no spaces): '${lines[i]}' (length: ${lines[i].length})")
        }
        
        val result = when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                Log.d(TAG, "Using TD3 format for DOB")
                if (lines.size >= 2 && lines[1].length >= 19) {
                    lines[1].substring(13, 19)
                } else {
                    Log.w(TAG, "TD3: Not enough data (have ${lines.size} lines, line[1] length=${if (lines.size >= 2) lines[1].length else 0})")
                    ""
                }
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                Log.d(TAG, "Using TD1 format for DOB")
                if (lines.size >= 2 && lines[1].length >= 6) {
                    lines[1].substring(0, 6)
                } else {
                    Log.w(TAG, "TD1: Not enough data (have ${lines.size} lines, need 2, line[1] length=${if (lines.size >= 2) lines[1].length else 0})")
                    ""
                }
            }
            else -> {
                Log.w(TAG, "Unknown document type: $documentType")
                ""
            }
        }
        Log.d(TAG, "extractDateOfBirth result: '$result'")
        return result
    }
    
    /**
     * Extract document expiry date from MRZ
     * TD3 (Passport) Line 2: ICAO 9303 Positions 21-26 (YYMMDD) = substring(21, 27)
     * TD1 (ID Card) Line 2: ICAO 9303 Positions 10-15 (YYMMDD) = substring(10, 16)
     * NOTE: MRZ lines never contain spaces - remove all spaces first (OCR artifacts)
     */
    fun extractExpiryDate(mrzText: String, documentType: String): String {
        val lines = mrzText.trim().split("\n")
            .map { it.trim().replace(" ", "") }  // Remove ALL spaces - MRZ never has spaces
            .filter { it.isNotEmpty() }
        
        Log.d(TAG, "extractExpiryDate: type=$documentType, lines=${lines.size}")
        for (i in lines.indices) {
            Log.d(TAG, "  Line $i (no spaces): '${lines[i]}' (length: ${lines[i].length})")
            // DEBUG: Show character-by-character breakdown
            if (i == 1 && lines[i].length > 20) {
                for (pos in 0 until minOf(44, lines[i].length)) {
                    Log.d(TAG, "    Pos[$pos] = '${lines[i][pos]}'")
                }
            }
        }
        
        val result = when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                Log.d(TAG, "Using TD3 format for expiry")
                if (lines.size >= 2 && lines[1].length >= 27) {
                    val extracted = lines[1].substring(21, 27)
                    Log.d(TAG, "TD3 Expiry extracted from (21,27): '$extracted'")
                    // Also log what's around this position
                    if (lines[1].length >= 33) {
                        Log.d(TAG, "  (20,26)='${lines[1].substring(20, 26)}' (one pos earlier)")
                        Log.d(TAG, "  (22,28)='${lines[1].substring(22, 28)}' (one pos later)")
                        Log.d(TAG, "  (27,32)='${lines[1].substring(27, 32)}' (old broken pos)")
                    }
                    extracted
                } else {
                    Log.w(TAG, "TD3: Not enough data (have ${lines.size} lines, line[1] length=${if (lines.size >= 2) lines[1].length else 0})")
                    ""
                }
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                Log.d(TAG, "Using TD1 format for expiry")
                if (lines.size >= 2 && lines[1].length >= 16) {
                    lines[1].substring(10, 16)
                } else {
                    Log.w(TAG, "TD1: Not enough data (have ${lines.size} lines, line[1] length=${if (lines.size >= 2) lines[1].length else 0})")
                    ""
                }
            }
            else -> {
                Log.w(TAG, "Unknown document type: $documentType")
                ""
            }
        }
        Log.d(TAG, "extractExpiryDate result: '$result'")
        return result
    }
    
    /**
     * Extract document number checksum from MRZ
     * TD3 (Passport) Line 2: Position 10 (1-indexed) = substring(9, 10) (0-indexed)
     * TD1 (ID Card) Line 1: Position 15 (1-indexed) = substring(14, 15) (0-indexed)
     */
    fun extractDocumentNumberChecksum(mrzText: String, documentType: String): String {
        val lines = mrzText.trim().split("\n")
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() }
        
        return when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                if (lines.size >= 2 && lines[1].length >= 10) {
                    lines[1].substring(9, 10)
                } else {
                    Log.w(TAG, "TD3: Cannot extract document checksum")
                    ""
                }
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                if (lines.size >= 1 && lines[0].length >= 15) {
                    lines[0].substring(14, 15)
                } else {
                    Log.w(TAG, "TD1: Cannot extract document checksum")
                    ""
                }
            }
            else -> ""
        }
    }
    
    /**
     * Extract DOB checksum from MRZ
     * TD3 (Passport) Line 2: Position 20 (1-indexed) = substring(19, 20) (0-indexed)
     * TD1 (ID Card) Line 2: Position 7 (1-indexed) = substring(6, 7) (0-indexed)
     */
    fun extractDateOfBirthChecksum(mrzText: String, documentType: String): String {
        val lines = mrzText.trim().split("\n")
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() }
        
        return when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                if (lines.size >= 2 && lines[1].length >= 20) {
                    lines[1].substring(19, 20)
                } else {
                    Log.w(TAG, "TD3: Cannot extract DOB checksum")
                    ""
                }
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                if (lines.size >= 2 && lines[1].length >= 7) {
                    lines[1].substring(6, 7)
                } else {
                    Log.w(TAG, "TD1: Cannot extract DOB checksum")
                    ""
                }
            }
            else -> ""
        }
    }
    
    /**
     * Extract Expiry checksum from MRZ
     * TD3 (Passport) Line 2: Position 28 (1-indexed) = substring(27, 28) (0-indexed)
     * TD1 (ID Card) Line 2: Position 17 (1-indexed) = substring(16, 17) (0-indexed)
     */
    fun extractExpiryDateChecksum(mrzText: String, documentType: String): String {
        val lines = mrzText.trim().split("\n")
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() }
        
        return when {
            documentType.contains("Passport", ignoreCase = true) || documentType.contains("TD3", ignoreCase = true) -> {
                if (lines.size >= 2 && lines[1].length >= 28) {
                    lines[1].substring(27, 28)
                } else {
                    Log.w(TAG, "TD3: Cannot extract expiry checksum")
                    ""
                }
            }
            documentType.contains("ID", ignoreCase = true) || documentType.contains("TD1", ignoreCase = true) -> {
                if (lines.size >= 2 && lines[1].length >= 17) {
                    lines[1].substring(16, 17)
                } else {
                    Log.w(TAG, "TD1: Cannot extract expiry checksum")
                    ""
                }
            }
            else -> ""
        }
    }
    
    /**
     * Construct complete BAC key string with all checksums
     * Format: DocumentNumber(9) + DocChecksum(1) + DOB(6) + DOBChecksum(1) + Expiry(6) + ExpiryChecksum(1)
     * Total: 24 characters
     */
    fun constructBACKeyString(
        mrzText: String,
        documentType: String
    ): String {
        val docNum = extractDocumentNumber(mrzText, documentType)
        val docChk = extractDocumentNumberChecksum(mrzText, documentType)
        val dob = extractDateOfBirth(mrzText, documentType)
        val dobChk = extractDateOfBirthChecksum(mrzText, documentType)
        val exp = extractExpiryDate(mrzText, documentType)
        val expChk = extractExpiryDateChecksum(mrzText, documentType)
        
        val bacKey = docNum + docChk + dob + dobChk + exp + expChk
        Log.d(TAG, "BAC Key String: $bacKey (length: ${bacKey.length})")
        Log.d(TAG, "  DocNum: $docNum")
        Log.d(TAG, "  DocChk: $docChk")
        Log.d(TAG, "  DOB: $dob")
        Log.d(TAG, "  DOBChk: $dobChk")
        Log.d(TAG, "  Expiry: $exp")
        Log.d(TAG, "  ExpChk: $expChk")
        
        return bacKey
    }
}
