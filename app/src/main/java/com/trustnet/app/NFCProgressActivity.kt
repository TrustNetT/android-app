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
    private var bacKey: ByteArray = byteArrayOf()
    private var documentType: String = ""
    private var isProcessing = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_progress)
        
        // Get BAC components from ConfirmationActivity
        // These are used to derive encryption keys for NFC chip authentication
        documentNumber = intent.getStringExtra("DOCUMENT_NUMBER") ?: ""
        dateOfBirth = intent.getStringExtra("DATE_OF_BIRTH") ?: ""
        dateOfExpiry = intent.getStringExtra("DATE_OF_EXPIRY") ?: ""
        documentType = intent.getStringExtra("DOCUMENT_TYPE") ?: "ID"
        
        Log.d(TAG, "NFCProgressActivity initialized:")
        Log.d(TAG, "  Document Type: '$documentType'")
        Log.d(TAG, "  Document Number: '$documentNumber'")
        Log.d(TAG, "  Date of Birth (YYMMDD): '$dateOfBirth'")
        Log.d(TAG, "  Date of Expiry (YYMMDD): '$dateOfExpiry'")
        
        // Derive BAC key from MRZ components
        deriveBACKey()
        
        // Initialize NFC and reader
        nfcAdapter = NfcAdapter.getDefaultAdapter(this) ?: run {
            Toast.makeText(this, "NFC not supported on this device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Route to correct reader based on document type
        when (documentType.uppercase()) {
            "PASSPORT" -> {
                Log.d(TAG, "→ Using TD3 PASSPORT reader (BAC authentication)")
                readerTD3 = PassportReaderTD3()
            }
            "ID", "ID_CARD" -> {
                Log.d(TAG, "→ Using TD1 ID CARD reader (PACE authentication)")
                readerPace = JmrtdPassportReaderPace()
            }
            else -> {
                Log.w(TAG, "Unknown document type: $documentType - defaulting to ID CARD (PACE)")
                readerPace = JmrtdPassportReaderPace()
            }
        }
        
        // Get view references
        statusTextView = findViewById(R.id.statusTextView)
        progressBar = findViewById(R.id.progressBar)
        
        val titleTextView: TextView = findViewById(R.id.titleTextView)
        titleTextView.text = "Scan NFC Chip"
        
        // Display BAC status
        if (bacKey.isNotEmpty()) {
            Log.d(TAG, "✓ BAC key derived successfully (${bacKey.size} bytes)")
            statusTextView.text = "✓ BAC authenticated\nReady to scan NFC\n\nHold phone over NFC chip..."
        } else {
            Log.w(TAG, "⚠ BAC key derivation failed - NFC read may fail")
            statusTextView.text = "Ready to scan NFC\n\nHold phone over NFC to read chip..."
        }
        
        Log.d(TAG, "NFCProgressActivity ready for NFC scanning")
    }
    
    /**
     * Derive BAC (Basic Access Control) key from MRZ components
     * Uses BACKeyService to compute SHA-1 hash of Document Number + DOB + Expiry
     */
    private fun deriveBACKey() {
        if (documentNumber.isEmpty() || dateOfBirth.isEmpty() || dateOfExpiry.isEmpty()) {
            Log.w(TAG, "Cannot derive BAC key - missing components")
            return
        }
        
        try {
            val bacService = BACKeyService()
            bacKey = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
            
            if (bacService.isValidBACKey(bacKey)) {
                Log.d(TAG, "✓ BAC key derived: ${bacKey.size} bytes")
            } else {
                Log.e(TAG, "✗ Invalid BAC key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving BAC key: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Enabling NFC reader mode")
        
        try {
            // Enable direct NFC callbacks (replaces intent filters)
            if (nfcAdapter.isEnabled) {
                nfcAdapter.enableReaderMode(this, this, READER_MODE_FLAGS, null)
                statusTextView.text = "✓ NFC Ready\n\nHold phone over NFC chip..."
            } else {
                Toast.makeText(this, "NFC is disabled. Please enable NFC in settings.", Toast.LENGTH_SHORT).show()
                statusTextView.text = "NFC is disabled"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling reader mode: ${e.message}")
            statusTextView.text = "Error: ${e.message}"
        }
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
                
                Log.d(TAG, "Calling readPassportFromTag with BAC key (${bacKey.size} bytes)")
                Log.d(TAG, "  Doc Number: '$documentNumber'")
                Log.d(TAG, "  DOB: '$dateOfBirth'")
                Log.d(TAG, "  Expiry: '$dateOfExpiry'")
                Log.d(TAG, "  Document Type: '$documentType'")
                
                // Read passport data using appropriate authentication method
                val passportData = when {
                    readerTD3 != null -> {
                        Log.d(TAG, "Reading via TD3 BAC reader...")
                        readerTD3!!.readPassportFromTag(tag, documentNumber, dateOfBirth, dateOfExpiry)
                    }
                    readerPace != null -> {
                        Log.d(TAG, "Reading via TD1 PACE reader...")
                        readerPace!!.readPassportFromTag(tag, documentNumber, dateOfBirth, dateOfExpiry)
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

