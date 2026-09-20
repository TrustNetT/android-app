package com.trustnetid.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trustnetid.AppVersion

/**
 * PassportConfirmationActivity
 * 
 * Displays extracted passport data from NFC chip for user verification
 * before proceeding to blockchain registration.
 * 
 * Flow:
 * NFCProgressActivity → PassportConfirmationActivity → MainActivity
 * 
 * Data received from: NFCProgressActivity (individual intent extras)
 * Data sent to: MainActivity (as individual intent extras)
 */
class PassportConfirmationActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PassportConfirmationActivity"
    }
    
    // Data fields from NFC extraction
    private var firstName: String = ""
    private var lastName: String = ""
    private var documentNumber: String = ""
    private var dateOfBirth: String = ""
    private var dateOfExpiry: String = ""
    private var gender: String = ""
    private var nationality: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_passport_confirmation)
        setHeaderVersion()
        
        Log.d(TAG, "PassportConfirmationActivity created")
        
        // Extract data from intent (passed from NFCProgressActivity)
        firstName = intent.getStringExtra("firstName") ?: ""
        lastName = intent.getStringExtra("lastName") ?: ""
        documentNumber = intent.getStringExtra("documentNumber") ?: ""
        dateOfBirth = intent.getStringExtra("dateOfBirth") ?: ""
        dateOfExpiry = intent.getStringExtra("dateOfExpiry") ?: ""
        gender = intent.getStringExtra("gender") ?: ""
        nationality = intent.getStringExtra("nationality") ?: ""
        
        Log.d(TAG, "╔════════════════════════════════════════╗")
        Log.d(TAG, "║  Extracted Passport Data              ║")
        Log.d(TAG, "╚════════════════════════════════════════╝")
        Log.d(TAG, "  Full Name: $firstName $lastName")
        Log.d(TAG, "  DOB: $dateOfBirth")
        Log.d(TAG, "  Gender: $gender")
        Log.d(TAG, "  Nationality: $nationality")
        Log.d(TAG, "  Document #: $documentNumber")
        Log.d(TAG, "  Expires: $dateOfExpiry")
        
        // Populate UI fields
        populateUI()
        
        // Set up button handlers
        setupButtons()
    }
    
    /**
     * Populate all UI fields with extracted data
     */
    private fun populateUI() {
        findViewById<TextView>(R.id.fullNameValue).text = "$firstName $lastName"
        findViewById<TextView>(R.id.dobValue).text = formatDate(dateOfBirth)
        findViewById<TextView>(R.id.genderValue).text = formatGender(gender)
        findViewById<TextView>(R.id.nationalityValue).text = nationality
        findViewById<TextView>(R.id.documentNumberValue).text = documentNumber
        findViewById<TextView>(R.id.expiryValue).text = formatDate(dateOfExpiry)
    }
    
    /**
     * Set up button click handlers
     */
    private fun setupButtons() {
        // Cancel button - return to document type selection
        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            Log.d(TAG, "User cancelled - returning to document type selection")
            val cancelIntent = Intent(this, DocumentTypeActivity::class.java)
            startActivity(cancelIntent)
            finish()
        }
        
        // Confirm button - proceed to main activity (blockchain registration)
        findViewById<Button>(R.id.confirmButton).setOnClickListener {
            Log.d(TAG, "User confirmed passport data - proceeding to registration")
            
            val confirmIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("firstName", firstName)
                putExtra("lastName", lastName)
                putExtra("documentNumber", documentNumber)
                putExtra("dateOfBirth", dateOfBirth)
                putExtra("dateOfExpiry", dateOfExpiry)
                putExtra("gender", gender)
                putExtra("nationality", nationality)
                putExtra("success", true)
            }
            
            startActivity(confirmIntent)
            finish()
        }
    }
    
    /**
     * Format date from YYMMDD to readable format
     * Example: "950615" → "15 Jun 1995"
     */
    private fun formatDate(dateStr: String): String {
        if (dateStr.length < 6) return dateStr
        
        return try {
            val year = dateStr.substring(0, 2).toInt()
            val month = dateStr.substring(2, 4).toInt()
            val day = dateStr.substring(4, 6).toInt()
            
            // Determine full year (19xx or 20xx based on value)
            val fullYear = if (year >= 50) 1900 + year else 2000 + year
            
            val monthNames = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                     "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val monthName = if (month in 1..12) monthNames[month] else "???"
            
            "$day $monthName $fullYear"
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing date: $dateStr", e)
            dateStr
        }
    }
    
    /**
     * Format gender code to readable text
     * 'M' → "Male", 'F' → "Female"
     */
    private fun formatGender(genderCode: String): String {
        return when (genderCode.uppercase()) {
            "M" -> "Male"
            "F" -> "Female"
            else -> genderCode.ifEmpty { "Unknown" }
        }
    }

    /**
     * Set the header version text from AppVersion
     * Called after setContentView to ensure header layout is loaded
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
