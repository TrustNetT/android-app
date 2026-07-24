package com.trustnet.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity for selecting document type before camera capture
 */
class DocumentTypeActivity : AppCompatActivity() {
    
    companion object {
        const val DOCUMENT_TYPE_KEY = "documentType"
        const val DOCUMENT_TYPE_ID = "ID"
        const val DOCUMENT_TYPE_PASSPORT = "Passport"
        private const val REQUEST_CODE_CAMERA = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_type)
        
        val idCardButton: Button = findViewById(R.id.idCardButton)
        val passportButton: Button = findViewById(R.id.passportButton)
        
        idCardButton.setOnClickListener {
            launchCamera(DOCUMENT_TYPE_ID)
        }
        
        passportButton.setOnClickListener {
            launchCamera(DOCUMENT_TYPE_PASSPORT)
        }
    }
    
    private fun launchCamera(documentType: String) {
        val cameraIntent = Intent(this, CameraActivity::class.java)
        cameraIntent.putExtra(DOCUMENT_TYPE_KEY, documentType)
        startActivityForResult(cameraIntent, REQUEST_CODE_CAMERA)
    }
    
    @Deprecated("Deprecated in API 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_CAMERA && resultCode == RESULT_OK && data != null) {
            val can = data.getStringExtra("CAN") ?: ""
            val documentType = data.getStringExtra(DOCUMENT_TYPE_KEY) ?: ""
            
            if (can.isNotEmpty() && can.length == 6) {
                // Launch NFC scanning with extracted CAN
                val nfcIntent = Intent(this, NFCProgressActivity::class.java)
                nfcIntent.putExtra("CAN", can)
                nfcIntent.putExtra(DOCUMENT_TYPE_KEY, documentType)
                startActivity(nfcIntent)
                finish()
            }
        }
    }
}
