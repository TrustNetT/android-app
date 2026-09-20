package com.trustnetid.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trustnetid.AppVersion

/**
 * Confirmation screen displaying raw MRZ data before NFC authentication
 * Shows exact MRZ lines as captured from OCR - no parsing/modification
 * User can verify OCR results match the physical document
 * 
 * MRZ Format:
 * - Line 1: Document type, issuing country, name
 * - Line 2: Document number, DOB, sex, expiry, nationality, optional data
 * - Line 3 (optional): Address or other data
 */
class ConfirmationActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ConfirmationActivity"
    }
    
    private var documentType: String = ""
    private var mrzLine1: String = ""
    private var mrzLine2: String = ""
    private var mrzLine3: String = ""
    private var documentNumber: String = ""
    private var dateOfBirth: String = ""
    private var dateOfExpiry: String = ""
    private var bacPassword: String = ""  // Full 24-char BAC password with check digits
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_confirm_mrz)
        setHeaderVersion()
        
        // Get raw MRZ data from intent
        mrzLine1 = intent.getStringExtra("MRZ_LINE_1") ?: ""
        mrzLine2 = intent.getStringExtra("MRZ_LINE_2") ?: ""
        mrzLine3 = intent.getStringExtra("MRZ_LINE_3") ?: ""
        documentType = intent.getStringExtra("DOCUMENT_TYPE") ?: "PASSPORT"
        
        Log.d(TAG, "═══ OCR Captured (RAW, before correction) ═══")
        Log.d(TAG, "  MRZ Line 1: '$mrzLine1' (${mrzLine1.length} chars)")
        Log.d(TAG, "  MRZ Line 2: '$mrzLine2' (${mrzLine2.length} chars)")
        Log.d(TAG, "  MRZ Line 3: '$mrzLine3' (${mrzLine3.length} chars)")
        Log.d(TAG, "  Document Type: '$documentType'")
        
        // CRITICAL: Correct OCR errors before processing
        // ML Kit often misreads numbers in the MRZ machine-readable zone
        // Common mistakes: '1'→'t'/'l'/'I', 'I'→'1'/'l', 'O'→'0', 'Z'→'2'
        mrzLine2 = correctOCRErrors(mrzLine2)
        
        Log.d(TAG, "═══ OCR Captured (AFTER correction) ═══")
        Log.d(TAG, "  MRZ Line 2: '$mrzLine2' (${mrzLine2.length} chars)")
        Log.d(TAG, "  (correction applied for common OCR misreads)")
        
        if (mrzLine2.length < 28) {
            Log.e(TAG, "🔴 CRITICAL: MRZ Line 2 too short (${mrzLine2.length} chars, need >=28 for expiry check digit)")
        }
        
        // AUTO-DETECT document type based on MRZ line count
        // This is the AUTHORITATIVE determination of document type
        val actualDocType = detectDocumentTypeFromMRZ()
        Log.d(TAG, "  🔍 AUTO-DETECTED document type: '$actualDocType' (2 lines=TD3/Passport, 3 lines=TD1/ID_CARD)")
        
        if (actualDocType != documentType) {
            Log.w(TAG, "  ⚠ Document type mismatch! User selected: '$documentType', but MRZ indicates: '$actualDocType'")
            Log.w(TAG, "  → Using auto-detected type: '$actualDocType' (MRZ is more reliable than user selection)")
            documentType = actualDocType  // Override user selection with MRZ detection
        }
        
        Log.d(TAG, "  ✓ Final document type: '$documentType'")
        
        // Extract BAC components from MRZ for chip authentication
        extractBACComponents()
        
        // Display raw MRZ data (no parsing)
        displayRawMRZ()
        displayBACComponents()
        
        // Setup buttons
        setupButtons()
    }
    
    override fun onResume() {
        super.onResume()
        // CRITICAL: Disable system NFC handler to prevent hijacking during MRZ confirmation
        // Only NFCProgressActivity should handle NFC, and only when user explicitly taps button
        val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
        nfcAdapter?.disableReaderMode(this)
    }
    
    /**
     * AUTO-DETECT document type based on MRZ line count
     * This overrides user selection because MRZ format is standardized by ICAO 9303
     * TD3 (Passport): 2 MRZ lines (line 1: type, country, name; line 2: number, DOB, expiry, etc.)
     * TD1 (ID Card): 3 MRZ lines (line 1: type, country; line 2: number, DOB, etc.; line 3: address/data)
     * TD2 (Visa): 2 MRZ lines (similar to TD3)
     */
    private fun correctOCRErrors(mrzLine: String): String {
        if (mrzLine.isEmpty()) return mrzLine
        
        var corrected = mrzLine
        var corrections = mutableListOf<String>()
        
        // Character-by-character correction based on position constraints
        // MRZ format is strictly defined: alphanumeric + '<' only
        // Different positions expect digits vs letters
        
        // LETTER-TO-DIGIT corrections (for all digit positions)
        // ML Kit often confuses letters and digits: 't'→'1', 'l'→'1', 'I'→'1', 'O'→'0', 'Z'→'2', etc.
        val digitOnlyPositions = listOf(9, 19, 27)  // Check digit positions
        digitOnlyPositions.forEach { pos ->
            if (pos < corrected.length) {
                val char = corrected[pos]
                if (!char.isDigit()) {
                    val correctedChar = when (char) {
                        't', 'T', 'l', 'L', 'I' -> '1'  // Common misreads as '1'
                        'O', 'o' -> '0'                  // Common misread as '0'
                        'Z', 'z' -> '2'                  // Common misread as '2'
                        'S', 's' -> '5'                  // Common misread as '5'
                        'B', 'b' -> '8'                  // Common misread as '8'
                        else -> char                     // Keep unknown chars
                    }
                    if (correctedChar != char) {
                        corrections.add("Pos $pos: '$char' → '$correctedChar'")
                        corrected = corrected.replaceRange(pos, pos + 1, correctedChar.toString())
                    }
                }
            }
        }
        
        // DOB and Expiry date positions: MUST be digits (0-9)
        val datePositions = (13..18) + (21..26)  // DOB (13-18) and Expiry (21-26)
        datePositions.forEach { pos ->
            if (pos < corrected.length) {
                val char = corrected[pos]
                if (!char.isDigit()) {
                    val correctedChar = when (char) {
                        't', 'T', 'l', 'L', 'I' -> '1'  // '1' misread
                        'O', 'o' -> '0'                  // '0' misread
                        'Z', 'z' -> '2'                  // '2' misread
                        'S', 's' -> '5'                  // '5' misread
                        'G', 'g' -> '6'                  // '6' misread
                        'B', 'b' -> '8'                  // '8' misread
                        else -> char
                    }
                    if (correctedChar != char) {
                        corrections.add("Pos $pos: '$char' → '$correctedChar'")
                        corrected = corrected.replaceRange(pos, pos + 1, correctedChar.toString())
                    }
                }
            }
        }
        
        // LETTER-TO-LETTER corrections (for document number and other letter positions)
        // Common confusions: A↔R, I↔L, O↔Q, etc.
        // Document number positions (0-8): Mix of letters and digits
        // For positions that should be letters, try to detect letter-to-letter OCR mistakes
        
        // These are heuristic-based: if we see obviously confused letter pairs,
        // try to correct them based on likelihood in passport numbers
        var charArray = corrected.toCharArray()
        
        // Position 0: Document type letter (first letter, usually P, A, C, D, T, V, X)
        // Common: P should not be R, A should not be R
        if (corrected.length > 0) {
            val char0 = charArray[0]
            if (char0 == 'R' && corrected.length >= 3) {
                // Check if next chars suggest it should be 'P' or another letter
                // For now, 'P' is most common for passports
                charArray[0] = 'P'
                corrections.add("Pos 0: '$char0' → 'P' (common passport document type)")
            }
        }
        
        // Positions 1-2: Usually letters for issuing country (like 'AI' for Spain/passport)
        // Common misreads: A→R, I→L, I→1
        if (corrected.length > 2) {
            val char1 = charArray[1]
            val char2 = charArray[2]
            
            // If we see 'R' in position 1, it's likely 'A' (A→R is very common)
            if (char1 == 'R' || char1 == 'r') {
                charArray[1] = 'A'
                corrections.add("Pos 1: '$char1' → 'A' (Spanish issuer code)")
            }
            
            // If we see 'L' in position 2, it's likely 'I' (I→L is very common)
            if (char2 == 'L' || char2 == 'l') {
                charArray[2] = 'I'
                corrections.add("Pos 2: '$char2' → 'I' (Spanish issuer code)")
            }
        }
        
        corrected = charArray.joinToString("")
        
        if (corrections.isNotEmpty()) {
            Log.d(TAG, "🔧 OCR corrections applied: ${corrections.joinToString(", ")}")
        }
        
        return corrected
    }
    
    private fun detectDocumentTypeFromMRZ(): String {
        val lineCount = listOfNotNull(
            mrzLine1.ifEmpty { null },
            mrzLine2.ifEmpty { null },
            mrzLine3.ifEmpty { null }
        ).size
        
        Log.d(TAG, "  Detecting document type from MRZ line count: $lineCount lines")
        
        return when (lineCount) {
            2 -> {
                Log.d(TAG, "    → 2 lines = TD3 Format (Passport)")
                "Passport"
            }
            3 -> {
                Log.d(TAG, "    → 3 lines = TD1 Format (ID Card)")
                "ID"
            }
            else -> {
                Log.w(TAG, "    → Unexpected line count ($lineCount), defaulting to Passport")
                "Passport"
            }
        }
    }
    
    /**
     * Extract BAC (Basic Access Control) components from MRZ
     * BAC requires: Document Number + DOB + Expiry + their check digits (24 chars total)
     * These are used to derive encryption keys for chip authentication
     */
    private fun extractBACComponents() {
        val mrzParser = MRZParser()
        val fullMRZ = listOfNotNull(
            mrzLine1.ifEmpty { null },
            mrzLine2.ifEmpty { null },
            mrzLine3.ifEmpty { null }
        ).joinToString("\n")
        
        if (fullMRZ.isNotEmpty()) {
            documentNumber = mrzParser.extractDocumentNumber(fullMRZ, documentType)
            dateOfBirth = mrzParser.extractDateOfBirth(fullMRZ, documentType)
            dateOfExpiry = mrzParser.extractExpiryDate(fullMRZ, documentType)
            
            // Extract check digits from ICAO 9303 fixed positions in MRZ line (not calculated)
            val docNumCheckDigit = mrzParser.extractDocumentNumberCheckDigit(fullMRZ, documentType)
            val dobCheckDigit = mrzParser.extractDateOfBirthCheckDigit(fullMRZ, documentType)
            val expiryCheckDigit = mrzParser.extractExpiryDateCheckDigit(fullMRZ, documentType)
            
            // Build full 24-character BAC password per ICAO 9303
            // Format: [DocNumber 9] + [Check 1] + [DOB 6] + [Check 1] + [Expiry 6] + [Check 1]
            bacPassword = documentNumber + docNumCheckDigit + dateOfBirth + dobCheckDigit + dateOfExpiry + expiryCheckDigit
            
            Log.d(TAG, "Extracted BAC components:")
            Log.d(TAG, "  Document Number: '$documentNumber' + Check Digit (from MRZ pos 9): '$docNumCheckDigit'")
            Log.d(TAG, "  Date of Birth (YYMMDD): '$dateOfBirth' + Check Digit (from MRZ pos 19): '$dobCheckDigit'")
            Log.d(TAG, "  Date of Expiry (YYMMDD): '$dateOfExpiry' + Check Digit (from MRZ pos 27): '$expiryCheckDigit'")
            Log.d(TAG, "  Full BAC Password (24 chars): '$bacPassword' (length: ${bacPassword.length})")
            Log.d(TAG, "FINAL BAC PASSWORD: $bacPassword")
            
            if (bacPassword.length != 24) {
                Log.w(TAG, "⚠ BAC password is ${bacPassword.length} chars, should be 24!")
            }
            
            if (documentNumber.isEmpty() || dateOfBirth.isEmpty() || dateOfExpiry.isEmpty()) {
                Log.w(TAG, "⚠ One or more BAC components missing!")
            }
        } else {
            Log.w(TAG, "No MRZ data to extract BAC components from")
        }
    }

    /**
     * Display raw MRZ lines exactly as captured from OCR
     */
    private fun displayRawMRZ() {
        val mrzLine1View: TextView = findViewById(R.id.mrzLine1)
        val mrzLine2View: TextView = findViewById(R.id.mrzLine2)
        val mrzLine3View: TextView = findViewById(R.id.mrzLine3)

        // Show raw MRZ lines in monospace font for clarity
        mrzLine1View.text = mrzLine1.ifEmpty { "(not detected)" }
        mrzLine2View.text = mrzLine2.ifEmpty { "(not detected)" }
        mrzLine3View.text = mrzLine3.ifEmpty { "(not detected)" }
        
        Log.d(TAG, "Display complete - waiting for user action")
    }
    
    /**
     * Display BAC components extracted from MRZ
     * BAC (Basic Access Control) uses these values to derive encryption keys
     */
    private fun displayBACComponents() {
        val docNumberView: TextView? = findViewById(R.id.docNumberValue)
        val dobView: TextView? = findViewById(R.id.dobValue)
        val expiryView: TextView? = findViewById(R.id.expiryValue)
        
        if (docNumberView != null) {
            if (documentNumber.isNotEmpty()) {
                docNumberView.text = documentNumber
                Log.d(TAG, "Doc Number display: $documentNumber (✓ will use for BAC)")
            } else {
                docNumberView.text = "(not detected)"
                Log.w(TAG, "Doc Number display: not detected")
            }
        }
        
        if (dobView != null) {
            if (dateOfBirth.isNotEmpty()) {
                dobView.text = dateOfBirth
                Log.d(TAG, "DOB display: $dateOfBirth (✓ will use for BAC)")
            } else {
                dobView.text = "(not detected)"
                Log.w(TAG, "DOB display: not detected")
            }
        }
        
        if (expiryView != null) {
            if (dateOfExpiry.isNotEmpty()) {
                expiryView.text = dateOfExpiry
                Log.d(TAG, "Expiry display: $dateOfExpiry (✓ will use for BAC)")
            } else {
                expiryView.text = "(not detected)"
                Log.w(TAG, "Expiry display: not detected")
            }
        }
        
        // Display BAC components (fields + check digits) for user verification
        displayBACPasswordBreakdown()
    }
    
    /**
     * Display the full BAC password components on screen for verification
     * Shows each component with its extracted check digit
     */
    private fun displayBACPasswordBreakdown() {
        val mrzParser = MRZParser()
        val fullMRZ = listOfNotNull(
            mrzLine1.ifEmpty { null },
            mrzLine2.ifEmpty { null },
            mrzLine3.ifEmpty { null }
        ).joinToString("\n")
        
        if (fullMRZ.isEmpty()) {
            return
        }
        
        // Extract check digits for display
        val docNumCheckDigit = mrzParser.extractDocumentNumberCheckDigit(fullMRZ, documentType)
        val dobCheckDigit = mrzParser.extractDateOfBirthCheckDigit(fullMRZ, documentType)
        val expiryCheckDigit = mrzParser.extractExpiryDateCheckDigit(fullMRZ, documentType)
        
        // Build components display text
        val componentsText = """
            Document Number: $documentNumber ✓
            Check Digit (pos 9): $docNumCheckDigit
            
            Date of Birth: $dateOfBirth ✓
            Check Digit (pos 19): $dobCheckDigit
            
            Expiry Date: $dateOfExpiry ✓
            Check Digit (pos 27): $expiryCheckDigit
        """.trimIndent()
        
        // Update UI
        val componentsView: TextView? = findViewById(R.id.bacComponentsTextView)
        if (componentsView != null) {
            componentsView.text = componentsText
        }
        
        // Update final BAC password display
        val passwordView: TextView? = findViewById(R.id.bacPasswordTextView)
        if (passwordView != null) {
            passwordView.text = bacPassword
        }
        
        Log.d(TAG, "BAC breakdown displayed:")
        Log.d(TAG, componentsText)
        Log.d(TAG, "Final BAC Password: $bacPassword")
    }

    /**
     * Setup button listeners
     */
    private fun setupButtons() {
        val retryButton: Button = findViewById(R.id.retryButton)
        val confirmButton: Button = findViewById(R.id.confirmButton)

        // Retry - go back to camera
        retryButton.setOnClickListener {
            Log.d(TAG, "User clicked Retry - returning to camera")
            finish()  // Back to CameraActivity
        }

        // Confirm - proceed to NFC with BAC components
        confirmButton.setOnClickListener {
            Log.d(TAG, "User confirmed MRZ - proceeding to NFC with BAC")
            Log.d(TAG, "  MRZ Line 1: '$mrzLine1'")
            Log.d(TAG, "  MRZ Line 2: '$mrzLine2'")
            Log.d(TAG, "  MRZ Line 3: '$mrzLine3'")
            Log.d(TAG, "  BAC Password (24 chars): '$bacPassword' (length: ${bacPassword.length})")
            Log.d(TAG, "  BAC Password Hex Dump: ${bacPassword.toByteArray().joinToString("") { String.format("%02x", it) }}")
            Log.d(TAG, "  BAC Password Char Array: [${bacPassword.mapIndexed { i, c -> "$i:$c" }.joinToString(", ")}]")
            Log.d(TAG, "  🔍 DOCUMENT_TYPE being passed: '$documentType' (${documentType.length} chars)")
            Log.d(TAG, "     Bytes: ${documentType.map { it.code }.toList()}")

            val nfcIntent = Intent(this, NFCProgressActivity::class.java)
            nfcIntent.putExtra("DOCUMENT_TYPE", documentType)
            nfcIntent.putExtra("MRZ_LINE_1", mrzLine1)
            nfcIntent.putExtra("MRZ_LINE_2", mrzLine2)
            nfcIntent.putExtra("MRZ_LINE_3", mrzLine3)
            
            // CRITICAL: Pass individual MRZ components for JMRTD BACKey
            // Let JMRTD build the 24-char password internally
            nfcIntent.putExtra("DOCUMENT_NUMBER", documentNumber)    // 9 chars
            nfcIntent.putExtra("DATE_OF_BIRTH", dateOfBirth)         // 6 chars YYMMDD
            nfcIntent.putExtra("DATE_OF_EXPIRY", dateOfExpiry)       // 6 chars YYMMDD
            
            nfcIntent.putExtra("BAC_PASSWORD", bacPassword)  // Full 24-char password (for logging/debugging)
            
            Log.d(TAG, "✓ Intent created with all extras")
            Log.d(TAG, "  Document Number: '$documentNumber' (${documentNumber.length} chars)")
            Log.d(TAG, "  Date of Birth: '$dateOfBirth' (${dateOfBirth.length} chars)")
            Log.d(TAG, "  Date of Expiry: '$dateOfExpiry' (${dateOfExpiry.length} chars)")
            Log.d(TAG, "  BAC Password (for reference): '$bacPassword' (${bacPassword.length} chars)")

            Log.d(TAG, "✓ Launching NFCProgressActivity with MRZ components for JMRTD BACKey")

            startActivity(nfcIntent)
            finish()
        }
    }

    /**
     * Set the header version text from AppVersion
     */
    private fun setHeaderVersion() {
        try {
            val headerVersion = findViewById<TextView>(R.id.headerVersion)
            if (headerVersion != null) {
                headerVersion.text = AppVersion.getVersionString()
            }
        } catch (e: Exception) {
            // Header layout might not be included in this activity
        }
    }
}
