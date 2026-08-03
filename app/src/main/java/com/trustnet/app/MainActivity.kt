package com.trustnet.app

import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    private var lastScannedCAN = ""  // Store CAN for retries
    private var nfcReadyToScan = false  // Track if we're waiting for NFC scan
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_CAMERA = 100
    }
    
    private lateinit var nfcAdapter: NfcAdapter
    private val passportValidator = PassportValidator()
    
    // View references
    private lateinit var resultContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available on this device")
            Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show()
        }
        
        // Check if we have NFC result data from the workflow
        val nfcResultData = intent.getBundleExtra("nfcResultData")
        
        if (nfcResultData != null) {
            // Display NFC scan results
            Log.d(TAG, "Displaying NFC scan results")
            setContentView(R.layout.activity_scan_result)
            resultContainer = findViewById(R.id.resultContainer)
            showScanResult(nfcResultData)
        } else {
            // Start with document type selection
            Log.d(TAG, "Starting document type selection workflow")
            val documentTypeIntent = Intent(this, DocumentTypeActivity::class.java)
            startActivity(documentTypeIntent)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "New intent received")
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // No NFC handling here - moved to NFCProgressActivity
    }

    override fun onPause() {
        super.onPause()
        // No NFC handling here - moved to NFCProgressActivity
    }


    private fun handleNfcIntent(intent: Intent) {
        // This method is now handled by NFCProgressActivity
        Log.d(TAG, "NFC handled in workflow")
    }

    private fun showScanResult(data: Bundle) {
        Log.d(TAG, "Displaying scan result")
        resultContainer = findViewById(R.id.resultContainer)
        
        // Populate text fields
        val firstNameView: TextView = findViewById(R.id.firstNameValue)
        val lastNameView: TextView = findViewById(R.id.lastNameValue)
        val genderView: TextView = findViewById(R.id.genderValue)
        val nationalityView: TextView = findViewById(R.id.nationalityValue)
        val documentNumberView: TextView = findViewById(R.id.documentNumberValue)
        val birthDateView: TextView = findViewById(R.id.birthDateValue)
        val expiryDateView: TextView = findViewById(R.id.expiryDateValue)
        val errorBox: LinearLayout = findViewById(R.id.errorBox)
        val errorMessage: TextView = findViewById(R.id.errorMessage)
        
        // Extract data from bundle
        val firstName = data.getString("firstName", "") ?: ""
        val lastName = data.getString("lastName", "") ?: ""
        val gender = data.getString("gender", "") ?: ""
        val nationality = data.getString("nationality", "") ?: ""
        val documentNumber = data.getString("documentNumber", "") ?: ""
        val birthDate = data.getString("birthDate", "") ?: ""
        val expiryDate = data.getString("expiryDate", "") ?: ""
        val paceError = data.getString("paceError", "") ?: ""
        
        // Check if data was successfully read
        val dataIsEmpty = firstName.isEmpty() && lastName.isEmpty() && gender.isEmpty()
        
        if (dataIsEmpty) {
            // Show error box explaining what happened
            errorBox.visibility = android.view.View.VISIBLE
            if (paceError.isNotEmpty()) {
                errorMessage.text = "PACE authentication failed: $paceError"
            } else {
                errorMessage.text = "No data could be read from the NFC chip. Please try again."
            }
        } else {
            errorBox.visibility = android.view.View.GONE
        }
        
        // Display data or show placeholder
        firstNameView.text = if (firstName.isNotEmpty()) firstName else "—"
        lastNameView.text = if (lastName.isNotEmpty()) lastName else "—"
        genderView.text = if (gender.isNotEmpty()) gender else "—"
        nationalityView.text = if (nationality.isNotEmpty()) nationality else "—"
        documentNumberView.text = if (documentNumber.isNotEmpty()) documentNumber else "—"
        birthDateView.text = if (birthDate.isNotEmpty()) birthDate else "—"
        expiryDateView.text = if (expiryDate.isNotEmpty()) expiryDate else "—"
        
        // Setup scan again button to restart workflow
        val scanAgainButton: Button = findViewById(R.id.scanAgainButton)
        scanAgainButton.setOnClickListener {
            Log.d(TAG, "Scan again button tapped - restarting workflow")
            val documentTypeIntent = Intent(this, DocumentTypeActivity::class.java)
            startActivity(documentTypeIntent)
            finish()
        }
        
        Toast.makeText(
            this,
            if (dataIsEmpty) "Scan failed - check error message below" else "✓ Successfully scanned government ID",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
