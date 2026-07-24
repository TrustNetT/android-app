package com.trustnet.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_mrz)
        
        // Get raw MRZ data from intent
        mrzLine1 = intent.getStringExtra("MRZ_LINE_1") ?: ""
        mrzLine2 = intent.getStringExtra("MRZ_LINE_2") ?: ""
        mrzLine3 = intent.getStringExtra("MRZ_LINE_3") ?: ""
        documentType = intent.getStringExtra("DOCUMENT_TYPE") ?: "PASSPORT"
        
        Log.d(TAG, "OCR Captured:")
        Log.d(TAG, "  MRZ Line 1: '$mrzLine1'")
        Log.d(TAG, "  MRZ Line 2: '$mrzLine2'")
        Log.d(TAG, "  MRZ Line 3: '$mrzLine3'")
        Log.d(TAG, "  Document Type: '$documentType'")
        
        // Extract BAC components from MRZ for chip authentication
        extractBACComponents()
        
        // Display raw MRZ data (no parsing)
        displayRawMRZ()
        displayBACComponents()
        
        // Setup buttons
        setupButtons()
    }
    
    /**
     * Extract BAC (Basic Access Control) components from MRZ
     * BAC requires: Document Number + Date of Birth + Date of Expiry
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
            
            Log.d(TAG, "✓ Extracted BAC components:")
            Log.d(TAG, "  Document Number: '$documentNumber'")
            Log.d(TAG, "  Date of Birth (YYMMDD): '$dateOfBirth'")
            Log.d(TAG, "  Date of Expiry (YYMMDD): '$dateOfExpiry'")
            
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
            Log.d(TAG, "  Document Number: '$documentNumber'")
            Log.d(TAG, "  Date of Birth: '$dateOfBirth'")
            Log.d(TAG, "  Date of Expiry: '$dateOfExpiry'")

            val nfcIntent = Intent(this, NFCProgressActivity::class.java)
            nfcIntent.putExtra("DOCUMENT_TYPE", documentType)
            nfcIntent.putExtra("MRZ_LINE_1", mrzLine1)
            nfcIntent.putExtra("MRZ_LINE_2", mrzLine2)
            nfcIntent.putExtra("MRZ_LINE_3", mrzLine3)
            nfcIntent.putExtra("DOCUMENT_NUMBER", documentNumber)
            nfcIntent.putExtra("DATE_OF_BIRTH", dateOfBirth)
            nfcIntent.putExtra("DATE_OF_EXPIRY", dateOfExpiry)

            startActivity(nfcIntent)
            finish()
        }
    }
}
