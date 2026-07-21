package com.trustnet.app

import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    private var lastScannedCAN = ""  // Store CAN for retries
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private lateinit var nfcAdapter: NfcAdapter
    private val nfcReader = GovernmentIDNFCReader()
    private val passportValidator = PassportValidator()
    
    // View references
    private lateinit var resultContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available on this device")
            Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "NFC available, ready to scan government ID")
        }
        
        // Setup scan button listener
        val scanButton: Button = findViewById(R.id.scanButton)
        scanButton.setOnClickListener {
            Log.d(TAG, "Scan button tapped - waiting for NFC tag...")
            Toast.makeText(this, "Ready to scan. Hold your government ID to the phone", Toast.LENGTH_SHORT).show()
        }
        
        // Handle NFC intent if this activity was launched by NFC tag detection
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "New NFC intent received")
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Enable NFC detection when app is in foreground
        try {
            // Tech lists for different NFC tag types (IsoDep, NfcA, NfcB)
            val techLists = arrayOf(
                arrayOf("android.nfc.tech.IsoDep"),
                arrayOf("android.nfc.tech.NfcA"),
                arrayOf("android.nfc.tech.NfcB"),
                arrayOf("android.nfc.tech.Ndef")
            )
            
            // Intent filters for TECH_DISCOVERED (catches most NFC tags)
            val intentFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            intentFilter.addCategory(Intent.CATEGORY_DEFAULT)
            
            nfcAdapter.enableForegroundDispatch(
                this, 
                createPendingIntent(), 
                arrayOf(intentFilter),
                techLists
            )
            Log.d(TAG, "NFC foreground dispatch enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling NFC foreground dispatch: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // Disable NFC detection when app goes to background
        nfcAdapter.disableForegroundDispatch(this)
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action
        Log.d(TAG, "handleNfcIntent called with action: $action")
        
        // Handle TECH_DISCOVERED (primary handler for NFC tags)
        if (action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            Log.d(TAG, "ACTION_TECH_DISCOVERED received")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                Log.d(TAG, "NFC tag detected via TECH_DISCOVERED: ${tag.id.toHexString()}")
                val techs = tag.techList.joinToString(", ")
                Log.d(TAG, "Tag technologies: $techs")
                processNfcTag(tag)
            } else {
                Log.w(TAG, "TECH_DISCOVERED received but no tag data")
            }
        }
        // Handle NDEF_DISCOVERED (for NDEF formatted tags)
        else if (action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            Log.d(TAG, "ACTION_NDEF_DISCOVERED received")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                Log.d(TAG, "NFC tag detected via NDEF_DISCOVERED: ${tag.id.toHexString()}")
                processNfcTag(tag)
            } else {
                Log.w(TAG, "NDEF_DISCOVERED received but no tag data")
            }
        }
        // Fallback: check if tag data is present even without specific action
        else {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                Log.d(TAG, "Found NFC tag in intent (no specific action)")
                processNfcTag(tag)
            } else {
                Log.d(TAG, "No NFC-related action, ignoring: $action")
            }
        }
    }

    private fun processNfcTag(tag: Tag) {
        Log.d(TAG, "Processing NFC tag with ID: ${tag.id.toHexString()}")
        
        // Check available technologies
        val availableTechs = tag.techList
        Log.d(TAG, "Available technologies: ${availableTechs.joinToString(", ")}")
        
        try {
            // Read government ID from NFC chip (optionally with PACE authentication if CAN provided)
            Log.d(TAG, "Calling GovernmentIDNFCReader.readFromTag() with CAN: ${if (lastScannedCAN.isEmpty()) "empty" else "provided"}...")
            val governmentIdData = nfcReader.readFromTag(tag, lastScannedCAN)
            
            if (governmentIdData != null) {
                Log.d(TAG, "Successfully read government ID from NFC tag")
                Log.d(TAG, "Document number: ${governmentIdData.documentNumber}")
                Log.d(TAG, "First name: ${governmentIdData.firstName}")
                Log.d(TAG, "Last name: ${governmentIdData.lastName}")
                Log.d(TAG, "Biometric data size: ${governmentIdData.biometricData.size} bytes")
                Log.d(TAG, "Raw data size: ${governmentIdData.rawData.size} bytes")
                
                // Display scanned information to user
                showScanResult(governmentIdData)
                
            } else {
                Log.e(TAG, "GovernmentIDNFCReader returned null")
                val errorMsg = if (lastScannedCAN.isEmpty()) {
                    "Files are protected. Provide CAN (Card Access Number) for authentication."
                } else {
                    "Failed to read government ID. Authentication may have failed."
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception processing NFC tag: ${e.javaClass.simpleName}: ${e.message}", e)
            e.printStackTrace()
            Toast.makeText(this, "Error reading tag: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPendingIntent(): android.app.PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Use FLAG_UPDATE_CURRENT for NFC (FLAG_IMMUTABLE can cause issues with some devices)
        return android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun showScanResult(data: GovernmentIDNFCReader.GovernmentIDData) {
        Log.d(TAG, "Displaying scan result")
        // Inflate result layout
        setContentView(R.layout.activity_scan_result)
        resultContainer = findViewById(R.id.resultContainer)
        
        // Populate text fields
        val firstNameView: TextView = findViewById(R.id.firstNameValue)
        val lastNameView: TextView = findViewById(R.id.lastNameValue)
        val genderView: TextView = findViewById(R.id.genderValue)
        val nationalityView: TextView = findViewById(R.id.nationalityValue)
        val documentNumberView: TextView = findViewById(R.id.documentNumberValue)
        val birthDateView: TextView = findViewById(R.id.birthDateValue)
        val expiryDateView: TextView = findViewById(R.id.expiryDateValue)
        
        // Display data or show "Protected" message
        firstNameView.text = if (data.firstName.isNotEmpty()) data.firstName else "[Protected - Requires PACE]"
        lastNameView.text = if (data.lastName.isNotEmpty()) data.lastName else "[Protected - Requires PACE]"
        genderView.text = if (data.gender.isNotEmpty()) data.gender else "[Protected - Requires PACE]"
        nationalityView.text = if (data.nationality.isNotEmpty()) data.nationality else "[Protected - Requires PACE]"
        documentNumberView.text = if (data.documentNumber.isNotEmpty()) data.documentNumber else "[Protected - Requires PACE]"
        birthDateView.text = if (data.birthDate.isNotEmpty()) data.birthDate else "[Protected - Requires PACE]"
        expiryDateView.text = if (data.expiryDate.isNotEmpty()) data.expiryDate else "[Protected - Requires PACE]"
        
        // Setup scan again button
        val scanAgainButton: Button = findViewById(R.id.scanAgainButton)
        scanAgainButton.setOnClickListener {
            Log.d(TAG, "Scan again button tapped")
            showScanScreen()
        }
        
        Toast.makeText(
            this,
            "✓ Successfully scanned government ID",
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun showScanScreen() {
        Log.d(TAG, "Returning to scan screen")
        setContentView(R.layout.activity_main)
        
        // Reinitialize NFC and setup button
        val scanButton: Button = findViewById(R.id.scanButton)
        scanButton.setOnClickListener {
            Log.d(TAG, "Scan button tapped - waiting for NFC tag...")
            Toast.makeText(this, "Ready to scan. Hold your government ID to the phone", Toast.LENGTH_SHORT).show()
        }
        
        // Re-enable NFC
        onResume()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
    
    /**
     * Set the Card Access Number (CAN) for PACE authentication
     * CAN is typically the first 6 digits of the document number
     */
    fun setCardAccessNumber(can: String) {
        lastScannedCAN = can.filter { it.isDigit() }.take(6)
        if (lastScannedCAN.length == 6) {
            Log.d(TAG, "Card Access Number set (first 3 digits: ${lastScannedCAN.take(3)}...)")
            Toast.makeText(this, "CAN set - next NFC scan will use authentication", Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "Invalid CAN: must be 6 digits")
            Toast.makeText(this, "CAN must be exactly 6 digits", Toast.LENGTH_SHORT).show()
            lastScannedCAN = ""
        }
    }
}
