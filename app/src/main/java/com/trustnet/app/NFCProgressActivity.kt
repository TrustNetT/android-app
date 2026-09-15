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
import com.trustnet.nfc.PassportReaderCallback
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * NFC Scanning Activity with Direct Callback Handling
 * 
 * Uses NfcAdapter.enableReaderMode() for direct tag callbacks instead of intent filters.
 * This provides app control over NFC detection without system dialogs or external intents.
 * Reads MRZ directly from chip (authoritative source), does not use OCR data.
 */
class NFCProgressActivity : AppCompatActivity(), NfcAdapter.ReaderCallback, PassportReaderCallback {
    
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
    private var documentType: String = ""
    private var isProcessing = false
    
    // DEBUG MODE: Set this to true to use hardcoded MRZ instead of OCR
    // This test isolates whether the failure is OCR or the authentication logic
    private val DEBUG_USE_HARDCODED_MRZ = false
    private val DEBUG_HARDCODED_BAC_PASSWORD = "PAI917686865103182904102"  // Edit this to test different MRZ values
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_progress)
        
        // Get MRZ components from ConfirmationActivity (not the 24-char password)
        // This way we let JMRTD build the password internally
        documentNumber = intent.getStringExtra("DOCUMENT_NUMBER") ?: ""
        dateOfBirth = intent.getStringExtra("DATE_OF_BIRTH") ?: ""
        dateOfExpiry = intent.getStringExtra("DATE_OF_EXPIRY") ?: ""
        bacPassword = intent.getStringExtra("BAC_PASSWORD") ?: ""  // For logging/debugging
        documentType = intent.getStringExtra("DOCUMENT_TYPE") ?: "ID"
        
        Log.d(TAG, "═══ NFCProgressActivity initialized ═══")
        Log.d(TAG, "Document Type: '$documentType'")
        Log.d(TAG, "MRZ Components (extracted from OCR):")
        Log.d(TAG, "  Document Number: '$documentNumber' (expected 9 chars)")
        Log.d(TAG, "  Date of Birth:   '$dateOfBirth' (expected 6 chars YYMMDD)")
        Log.d(TAG, "  Date of Expiry:  '$dateOfExpiry' (expected 6 chars YYMMDD)")
        Log.d(TAG, "  Full BAC Password (for reference): '$bacPassword' (${bacPassword.length} chars)")
        
        // DEBUG MODE: Override with hardcoded MRZ if flag is set
        if (DEBUG_USE_HARDCODED_MRZ) {
            Log.w(TAG, "🔴 DEBUG MODE ENABLED: Using hardcoded MRZ components instead of OCR")
            Log.w(TAG, "   Hardcoded BAC: '$DEBUG_HARDCODED_BAC_PASSWORD'")
            // For hardcoded test, extract components from the hardcoded password
            // Standard format: [DocNumber 9] + [Check 1] + [DOB 6] + [Check 1] + [Expiry 6] + [Check 1]
            documentNumber = DEBUG_HARDCODED_BAC_PASSWORD.substring(0, 9)      // Positions 0-8
            dateOfBirth = DEBUG_HARDCODED_BAC_PASSWORD.substring(10, 16)       // Positions 10-15  
            dateOfExpiry = DEBUG_HARDCODED_BAC_PASSWORD.substring(18, 24)      // Positions 18-23
            bacPassword = DEBUG_HARDCODED_BAC_PASSWORD
        }
        
        // CRITICAL: Validate MRZ components
        if (documentNumber.isEmpty() || dateOfBirth.isEmpty() || dateOfExpiry.isEmpty()) {
            Log.e(TAG, "🔴 CRITICAL ERROR: MRZ components are missing!")
            Log.e(TAG, "   Document Number: '${documentNumber}' (got ${documentNumber.length}, expected 9)")
            Log.e(TAG, "   Date of Birth: '${dateOfBirth}' (got ${dateOfBirth.length}, expected 6)")
            Log.e(TAG, "   Date of Expiry: '${dateOfExpiry}' (got ${dateOfExpiry.length}, expected 6)")
        } else if (documentNumber.length != 9 || dateOfBirth.length != 6 || dateOfExpiry.length != 6) {
            Log.w(TAG, "⚠️ WARNING: MRZ components have unexpected lengths")
            Log.w(TAG, "   Document Number: ${documentNumber.length} (expected 9)")
            Log.w(TAG, "   Date of Birth: ${dateOfBirth.length} (expected 6)")
            Log.w(TAG, "   Date of Expiry: ${dateOfExpiry.length} (expected 6)")
            Log.w(TAG, "   NFC authentication may fail")
        } else {
            Log.d(TAG, "✓ MRZ components are valid and ready for JMRTD BAC")
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
                readerTD3!!.setStatusCallback(this)  // Set callback for UI updates
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
        Log.d(TAG, "onResume: Activity resumed")
        
        // CRITICAL: If we're in the middle of an NFC transaction, RE-ENABLE reader mode
        // onPause() disabled it to prevent interruption, now we're back so restore exclusive control
        if (isProcessing) {
            Log.d(TAG, "onResume: Transaction in progress, RE-ENABLING NFC reader mode to maintain exclusive control")
            try {
                nfcAdapter.enableReaderMode(this, this, READER_MODE_FLAGS, null)
                Log.d(TAG, "✓ NFC reader mode RE-ENABLED during ongoing transaction")
            } catch (e: Exception) {
                Log.e(TAG, "Error re-enabling reader mode: ${e.message}")
            }
        } else {
            Log.d(TAG, "onResume: No active transaction, waiting for button tap")
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // CRITICAL FIX: Only disable reader mode if transaction is complete
        // During active transaction, briefly disable to prevent interference,
        // then re-enable in onResume() to maintain exclusive NFC control
        if (isProcessing) {
            Log.d(TAG, "onPause: Transaction in progress, temporarily disabling reader mode")
            try {
                if (::nfcAdapter.isInitialized) {
                    nfcAdapter.disableReaderMode(this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error temporarily disabling reader mode: ${e.message}")
            }
            Log.d(TAG, "  (Will be RE-ENABLED in onResume)")
        } else {
            Log.d(TAG, "onPause: No active transaction, disabling reader mode permanently")
            try {
                if (::nfcAdapter.isInitialized) {
                    nfcAdapter.disableReaderMode(this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error disabling reader mode: ${e.message}")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Cleaning up resources")
        
        // Ensure reader mode is disabled during cleanup
        try {
            if (::nfcAdapter.isInitialized && isProcessing) {
                Log.d(TAG, "onDestroy: Force-disabling reader mode during activity destruction")
                nfcAdapter.disableReaderMode(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during destruction cleanup: ${e.message}")
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
        
        // Validate MRZ components before attempting BAC (JMRTD will build the 24-char password internally)
        if (documentNumber.isEmpty() || dateOfBirth.isEmpty() || dateOfExpiry.isEmpty()) {
            Log.e(TAG, "MRZ components not available - cannot authenticate")
            Log.e(TAG, "  Document Number: '${documentNumber}' (length: ${documentNumber.length})")
            Log.e(TAG, "  Date of Birth: '${dateOfBirth}' (length: ${dateOfBirth.length})")
            Log.e(TAG, "  Date of Expiry: '${dateOfExpiry}' (length: ${dateOfExpiry.length})")
            runOnUiThread {
                Toast.makeText(this, "MRZ components missing", Toast.LENGTH_SHORT).show()
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
                
                Log.d(TAG, "Calling readPassportFromTag with MRZ components")
                Log.d(TAG, "  Document Number: '$documentNumber'")
                Log.d(TAG, "  Date of Birth: '$dateOfBirth'")
                Log.d(TAG, "  Date of Expiry: '$dateOfExpiry'")
                
                // Read passport data using appropriate authentication method
                // Pass individual MRZ components - let JMRTD build the 24-char password internally
                val passportData = when {
                    readerTD3 != null -> {
                        Log.d(TAG, "Reading via TD3 BAC reader using BACKey...")
                        Log.d(TAG, "JMRTD will internally derive keys per ICAO 9303")
                        readerTD3!!.readPassportFromTag(tag, documentNumber, dateOfBirth, dateOfExpiry, bacPassword)
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
                    Log.d(TAG, "Gender: ${passportData.gender}")
                    Log.d(TAG, "Nationality: ${passportData.nationality}")
                    
                    // CRITICAL: Show extracted data on screen so user can verify what was read
                    // Display for 4 seconds so user can read all the extracted fields
                    val displayText = """✅ SUCCESS! Data read from chip:
                        
Name: ${passportData.firstName} ${passportData.lastName}
Document: ${passportData.documentNumber}
DOB: ${passportData.dateOfBirth}
Expiry: ${passportData.dateOfExpiry}
Gender: ${passportData.gender}
Nationality: ${passportData.nationality}

(Navigating in 3 seconds...)
""".trimIndent()
                    
                    runOnUiThread {
                        statusTextView.text = displayText
                        progressBar.visibility = View.GONE
                    }
                    
                    Log.d(TAG, "Displaying extracted data for 3 seconds before navigation...")
                    
                    // Wait 3 seconds so user can verify the data that was extracted
                    delay(3000)
                    
                    Log.d(TAG, "3 seconds elapsed, now navigating to MainActivity...")
                    
                    // Mark transaction complete and disable reader mode
                    finalizeNFCTransaction()
                    
                    // Navigate to passport confirmation screen for user verification
                    val resultIntent = Intent(this@NFCProgressActivity, PassportConfirmationActivity::class.java).apply {
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
                    
                    // Mark transaction complete and disable reader mode
                    finalizeNFCTransaction()
                    
                    runOnUiThread {
                        statusTextView.text = "❌ Read failed:\n${passportData.error}"
                        Toast.makeText(this@NFCProgressActivity, "Error: ${passportData.error}", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception reading chip: ${e.message}", e)
                
                // Mark transaction complete and disable reader mode
                finalizeNFCTransaction()
                
                runOnUiThread {
                    statusTextView.text = "❌ Error:\n${e.message}"
                    Toast.makeText(this@NFCProgressActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Finalize NFC transaction: disable reader mode and reset state
     * Called when transaction succeeds or fails
     */
    private fun finalizeNFCTransaction() {
        Log.d(TAG, "finalizeNFCTransaction: Disabling NFC reader mode, transaction complete")
        isProcessing = false
        
        try {
            if (::nfcAdapter.isInitialized) {
                nfcAdapter.disableReaderMode(this)
                Log.d(TAG, "✓ NFC reader mode disabled, resources released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during finalization: ${e.message}")
        }
    }
    
    /**
     * PassportReaderCallback implementation: Receive status updates from reader
     */
    override fun onStatus(message: String) {
        Log.d(TAG, "→ STATUS: $message")
        runOnUiThread {
            statusTextView.text = statusTextView.text.toString() + "\n→ $message"
            // Keep it scrolled to bottom if it's a scroll view
            if (statusTextView.parent is android.widget.ScrollView) {
                (statusTextView.parent as android.widget.ScrollView).post {
                    (statusTextView.parent as android.widget.ScrollView).fullScroll(
                        android.widget.ScrollView.FOCUS_DOWN
                    )
                }
            }
        }
    }
    
    override fun onError(message: String) {
        Log.e(TAG, "✗ ERROR: $message")
        runOnUiThread {
            statusTextView.text = statusTextView.text.toString() + "\n✗ ERROR: $message"
            Toast.makeText(this@NFCProgressActivity, "❌ $message", Toast.LENGTH_LONG).show()
        }
    }
}

