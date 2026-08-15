package com.trustnet.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.trustnet.nfc.JmrtdPassportReaderPace
import com.trustnet.nfc.PassportReaderTD3
import com.trustnet.nfc.PassportData
import kotlinx.coroutines.launch

/**
 * NFC Scanning Activity with Direct Callback Handling
 * 
 * Uses NfcAdapter.enableReaderMode() for direct tag callbacks instead of intent filters.
 * This provides app control over NFC detection without system dialogs or external intents.
 * Reads MRZ directly from chip (authoritative source), does not use OCR data.
 */
class NFCProgressActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    
    companion object {
        private const val TAG = "NFCProgressActivity"
        private const val READER_MODE_FLAGS = 
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
    
    private lateinit var nfcAdapter: NfcAdapter
    private var readerTD3: PassportReaderTD3? = null
    private var readerPace: JmrtdPassportReaderPace? = null
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    
    private var documentNumber: String = ""
    private var dateOfBirth: String = ""
    private var dateOfExpiry: String = ""
    private var bacPassword: String = ""  // Full 24-char BAC password with check digits
    private var bacKey: ByteArray = byteArrayOf()
    private var documentType: String = ""
    private var isProcessing = false
    
    // DEBUG MODE: Set this to true to use hardcoded MRZ instead of OCR
    // This test isolates whether the failure is OCR or the authentication logic
    private val DEBUG_USE_HARDCODED_MRZ = false
    private val DEBUG_HARDCODED_BAC_PASSWORD = "PAI917686865103182904102"  // Edit this to test different MRZ values
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_progress)
        
        // Get BAC password (24-char with check digits) from ConfirmationActivity
        bacPassword = intent.getStringExtra("BAC_PASSWORD") ?: ""
        documentType = intent.getStringExtra("DOCUMENT_TYPE") ?: "ID"
        
        Log.d(TAG, "═══ NFCProgressActivity initialized ═══")
        Log.d(TAG, "Document Type: '$documentType'")
        Log.d(TAG, "BAC Password RECEIVED: '$bacPassword'")
        Log.d(TAG, "BAC Password Length: ${bacPassword.length} (expected: 24)")
        Log.d(TAG, "BAC Password Hex Dump: ${bacPassword.toByteArray().joinToString("") { String.format("%02x", it) }}")
        Log.d(TAG, "BAC Password Char Array: [${bacPassword.mapIndexed { i, c -> "$i:$c" }.joinToString(", ")}]")
        
        // DEBUG MODE: Override with hardcoded MRZ if flag is set
        if (DEBUG_USE_HARDCODED_MRZ) {
            Log.w(TAG, "🔴 DEBUG MODE ENABLED: Using hardcoded BAC password instead of OCR")
            Log.w(TAG, "   Hardcoded BAC: '$DEBUG_HARDCODED_BAC_PASSWORD'")
            bacPassword = DEBUG_HARDCODED_BAC_PASSWORD
        }
        
        // CRITICAL: Check if BAC password is valid
        if (bacPassword.isEmpty()) {
            Log.e(TAG, "🔴 CRITICAL ERROR: BAC Password is EMPTY!")
            Log.e(TAG, "   App was launched without going through OCR/ConfirmationActivity")
            Log.e(TAG, "   Cannot proceed with NFC authentication")
        } else if (bacPassword.length != 24) {
            Log.w(TAG, "⚠️ WARNING: BAC Password length is ${bacPassword.length}, expected 24")
            Log.w(TAG, "   NFC authentication may fail")
        } else {
            Log.d(TAG, "✓ BAC Password is valid (24 chars)")
            // CRITICAL: Derive BAC key from password IMMEDIATELY
            deriveBACKey()
        }
        
        // Initialize NFC and reader
        nfcAdapter = NfcAdapter.getDefaultAdapter(this) ?: run {
            Toast.makeText(this, "NFC not supported on this device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Route to correct reader based on document type
        Log.d(TAG, "📊 READER SELECTION DEBUG:")
        Log.d(TAG, "   documentType raw value: '$documentType'")
        Log.d(TAG, "   documentType.uppercase(): '${documentType.uppercase()}'")
        Log.d(TAG, "   Checking conditions:")
        Log.d(TAG, "     contains('Passport', ignoreCase=true): ${documentType.contains("Passport", ignoreCase = true)}")
        Log.d(TAG, "     contains('TD3', ignoreCase=true): ${documentType.contains("TD3", ignoreCase = true)}")
        Log.d(TAG, "     .uppercase() == 'PASSPORT': ${documentType.uppercase() == "PASSPORT"}")
        Log.d(TAG, "     contains('ID', ignoreCase=true): ${documentType.contains("ID", ignoreCase = true)}")
        Log.d(TAG, "     contains('ID_CARD', ignoreCase=true): ${documentType.contains("ID_CARD", ignoreCase = true)}")
        
        when (documentType.uppercase()) {
            "PASSPORT" -> {
                Log.d(TAG, "PASSPORT (TD3) → Using BAC protocol (ICAO 9303)")
                readerTD3 = PassportReaderTD3()
            }
            "ID", "ID_CARD" -> {
                Log.d(TAG, "✓✓✓ MATCHED 'ID' or 'ID_CARD' → USING TD1 PACE READER ✓✓✓")
                Log.d(TAG, "READER SELECTED: JmrtdPassportReaderPace (PACE authentication for ID cards)")
                readerPace = JmrtdPassportReaderPace()
            }
            else -> {
                Log.e(TAG, "🔴 CRITICAL: Unknown document type: '$documentType' (${documentType.length} chars)")
                Log.e(TAG, "    Bytes: ${documentType.map { it.code }.toList()}")
                Log.e(TAG, "    Cannot proceed with NFC authentication - document type mismatch")
                Toast.makeText(this, "Unknown document type: $documentType", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        
        // Get view references
        statusTextView = findViewById(R.id.statusTextView)
        progressBar = findViewById(R.id.progressBar)
        
        val titleTextView: TextView = findViewById(R.id.titleTextView)
        titleTextView.text = "Scan NFC Chip"
        
        // Display NFC ready status
        // (JMRTD will handle key derivation internally)
        if (bacPassword.isNotEmpty() && bacPassword.length == 24) {
            Log.d(TAG, "✓ BAC password validated (24 chars)")
            statusTextView.text = "✓ Ready for NFC\n\nTap 'START NFC SCAN' button to begin"
        } else {
            Log.w(TAG, "⚠ BAC password invalid - NFC read may fail")
            statusTextView.text = "Ready to scan NFC\n\nTap 'START NFC SCAN' button to begin"
        }
        
        // Wire up START NFC SCAN button
        val scanButton: android.widget.Button = findViewById(R.id.scanButton)
        scanButton.setOnClickListener { 
            Log.d(TAG, "🔵 START NFC SCAN button clicked")
            startNFCReader()
        }
        
        // Add EDIT MRZ button for OCR correction
        val editButton: android.widget.Button? = findViewById(R.id.editButton)
        if (editButton != null) {
            editButton.setOnClickListener {
                Log.d(TAG, "🔵 EDIT MRZ button clicked")
                showMRZEditDialog()
            }
        }
        
        Log.d(TAG, "NFCProgressActivity ready - waiting for button tap")
    }
    
    /**
     * Derive BAC (Basic Access Control) key from 24-character MRZ password
     * The password includes all check digits per ICAO 9303
     */
    private fun deriveBACKey() {
        if (bacPassword.isEmpty()) {
            Log.w(TAG, "Cannot derive BAC key - BAC password is empty")
            return
        }
        
        if (bacPassword.length != 24) {
            Log.w(TAG, "⚠ BAC password is ${bacPassword.length} chars, expected 24. May fail NFC authentication.")
        }
        
        try {
            val bacService = BACKeyService()
            bacKey = bacService.deriveBACKey(bacPassword)
            
            if (bacService.isValidBACKey(bacKey)) {
                Log.d(TAG, "✓ BAC key derived from 24-char password: ${bacKey.size} bytes")
            } else {
                Log.e(TAG, "✗ Invalid BAC key derived")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving BAC key: ${e.message}")
        }
    }
    
    /**
     * Show dialog to manually edit BAC password
     * This allows users to correct OCR errors before NFC authentication
     */
    private fun showMRZEditDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Edit BAC Password")
        builder.setMessage("Enter the 24-character BAC password from your passport MRZ:\n\n[Doc# 9 chars] + [Doc Check 1] + [DOB 6 chars] + [DOB Check 1] + [Expiry 6 chars] + [Expiry Check 1]")
        
        val input = android.widget.EditText(this)
        input.setText(bacPassword)
        input.setSelection(bacPassword.length)
        input.hint = "e.g., PAI917686865103182904102"
        input.textSize = 14f
        
        builder.setView(input)
        builder.setPositiveButton("OK") { dialog, _ ->
            val newPassword = input.text.toString().trim().uppercase()
            if (newPassword.length == 24) {
                Log.d(TAG, "User entered BAC password: '$newPassword'")
                
                // Validate that all characters are valid MRZ (A-Z, 0-9, <)
                if (!newPassword.all { it.isLetterOrDigit() || it == '<' }) {
                    Toast.makeText(this@NFCProgressActivity, "BAC password can only contain letters, digits, and '<' characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                bacPassword = newPassword
                deriveBACKey()
                statusTextView.text = "✓ BAC Updated\n\nTap 'START NFC SCAN' to try again"
                dialog.dismiss()
                
                Toast.makeText(this@NFCProgressActivity, "MRZ updated. Ready to scan NFC chip.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@NFCProgressActivity, "BAC password must be exactly 24 characters (got ${newPassword.length})", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }
    
    /**
     * Start NFC reader mode when user taps the button
     * This prevents automatic NFC detection and gives user control
     */
    private fun startNFCReader() {
        Log.d(TAG, "startNFCReader: Enabling NFC reader mode on button tap")
        
        try {
            if (!nfcAdapter.isEnabled) {
                Toast.makeText(this, "NFC is disabled. Please enable NFC in settings.", Toast.LENGTH_SHORT).show()
                statusTextView.text = "NFC is disabled"
                return
            }
            
            // Enable direct NFC callbacks (replaces intent filters)
            nfcAdapter.enableReaderMode(this, this, READER_MODE_FLAGS, null)
            statusTextView.text = "✓ NFC Enabled\n\nHold phone over NFC chip..."
            
            // Disable button to prevent multiple taps
            findViewById<android.widget.Button>(R.id.scanButton).isEnabled = false
            
            Log.d(TAG, "✓ NFC reader mode enabled, waiting for tag...")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling reader mode: ${e.message}")
            statusTextView.text = "Error: ${e.message}"
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Activity resumed (NFC not auto-enabled, waiting for button tap)")
        // NFC reader mode is NOT enabled here - user must tap the button
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Disabling NFC reader mode")
        
        try {
            if (::nfcAdapter.isInitialized) {
                nfcAdapter.disableReaderMode(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling reader mode: ${e.message}")
        }
    }
    
    /**
     * ReaderCallback implementation - called directly when NFC tag is detected
     * Reads MRZ directly from chip using BAC authentication
     */
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null || isProcessing) {
            Log.w(TAG, "Tag is null or already processing")
            runOnUiThread {
                Toast.makeText(this, "Tag null or already processing", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        if (bacKey.isEmpty()) {
            Log.e(TAG, "BAC key not available - cannot authenticate")
            runOnUiThread {
                Toast.makeText(this, "BAC key not available", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        isProcessing = true
        Log.d(TAG, "onTagDiscovered called - Reading from chip via BAC authentication")
        
        lifecycleScope.launch {
            try {
                runOnUiThread {
                    statusTextView.text = "Authenticating with BAC..."
                    progressBar.visibility = View.VISIBLE
                }
                
                Log.d(TAG, "Calling readPassportFromTag with BAC password")
                Log.d(TAG, "  BAC Password (24 chars): '$bacPassword' (length: ${bacPassword.length})")
                Log.d(TAG, "  Document Type: '$documentType'")
                
                // Read passport data using appropriate authentication method
                // JMRTD handles all key derivation internally - just pass the full BAC password
                val passportData = when {
                    readerTD3 != null -> {
                        Log.d(TAG, "Reading via TD3 BAC reader with full 24-char BAC password...")
                        Log.d(TAG, "JMRTD will internally derive key and perform authentication")
                        readerTD3!!.readPassportFromTag(tag, bacPassword)
                    }
                    readerPace != null -> {
                        Log.d(TAG, "Reading via TD1 PACE reader...")
                        readerPace!!.readPassportFromTag(tag, bacPassword)
                    }
                    else -> PassportData(
                        success = false,
                        error = "No reader initialized for document type: $documentType"
                    )
                }
                
                if (passportData.success) {
                    Log.d(TAG, "✓✓✓ Passport read SUCCESSFUL from chip ✓✓✓")
                    Log.d(TAG, "Names: ${passportData.firstName} ${passportData.lastName}")
                    Log.d(TAG, "Document: ${passportData.documentNumber}")
                    Log.d(TAG, "DOB: ${passportData.dateOfBirth}")
                    Log.d(TAG, "Expiry: ${passportData.dateOfExpiry}")
                    
                    // Return to main activity with chip data
                    val resultIntent = Intent(this@NFCProgressActivity, MainActivity::class.java).apply {
                        putExtra("firstName", passportData.firstName)
                        putExtra("lastName", passportData.lastName)
                        putExtra("documentNumber", passportData.documentNumber)
                        putExtra("dateOfBirth", passportData.dateOfBirth)
                        putExtra("dateOfExpiry", passportData.dateOfExpiry)
                        putExtra("gender", passportData.gender)
                        putExtra("nationality", passportData.nationality)
                        putExtra("success", true)
                    }
                    
                    startActivity(resultIntent)
                    finish()
                } else {
                    Log.e(TAG, "Failed to read chip: ${passportData.error}")
                    runOnUiThread {
                        statusTextView.text = "❌ Read failed:\n${passportData.error}"
                        Toast.makeText(this@NFCProgressActivity, "Error: ${passportData.error}", Toast.LENGTH_LONG).show()
                        isProcessing = false
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception reading chip: ${e.message}", e)
                runOnUiThread {
                    statusTextView.text = "❌ Error:\n${e.message}"
                    Toast.makeText(this@NFCProgressActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    isProcessing = false
                }
            }
        }
    }
}

