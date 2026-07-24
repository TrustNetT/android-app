# Critical Rebuild Specification - Camera2 + App-Controlled NFC

## ABSOLUTE RULES FOR THIS REBUILD

1. **DO NOT delete or modify any existing working code**
   - MRZParser.kt - UNTOUCHABLE
   - GovernmentIDNFCReader.kt - UNTOUCHABLE  
   - PACEAuthenticator.kt - UNTOUCHABLE
   - MainActivity.kt - MINIMAL CHANGES ONLY

2. **Preserve ALL branding**
   - Logo stays in SplashActivity
   - Purple colors unchanged
   - All strings with "TrustNet" remain
   - All button colors unchanged

3. **Create ONLY what's needed**
   - CameraActivity.kt - REWRITE (only this, not create new files for camera)
   - NFCProgressActivity.kt - REWRITE NFC parts only
   - BACKeyService.kt - CREATE (brand new, not modifying existing)
   - build.gradle - ADD dependencies only
   - activity_camera.xml - ADD SurfaceView element only
   - AndroidManifest.xml - REMOVE intent filters from NFC section only

---

## CameraActivity.kt - Complete Rebuild

**Current state**: Uses Intent.ACTION_IMAGE_CAPTURE (broken - external camera app)  
**New state**: Embedded Camera2 with auto-capture

```kotlin
package com.trustnet.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {
    
    companion object {
        private const val TAG = "CameraActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
    }
    
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var mrzParser: MRZParser
    private lateinit var cameraManager: CameraManager
    
    private var documentType: String = ""
    private var mrzDetected = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        
        // Get document type from intent
        documentType = intent.getStringExtra("documentType") ?: "ID"
        
        // Initialize views
        surfaceView = findViewById(R.id.surfaceView)
        statusTextView = findViewById(R.id.statusTextView)
        progressBar = findViewById(R.id.processingProgressBar)
        
        val titleTextView: TextView = findViewById(R.id.titleTextView)
        titleTextView.text = "Capture $documentType Document"
        
        // Initialize MRZ parser and camera
        mrzParser = MRZParser()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            initializeCamera()
        }
        
        // Set up surface holder callback for camera preview
        surfaceView.holder.addCallback(this)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun initializeCamera() {
        statusTextView.text = "Initializing camera..."
        statusTextView.setTextColor(android.graphics.Color.BLACK)
        progressBar.visibility = View.VISIBLE
        
        // Camera2 implementation here
        // For now, placeholder
        Log.d(TAG, "Camera initialized")
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        // Start camera preview on surface
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed")
        // Adjust camera for new surface size
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        // Release camera
    }
    
    private fun processFrame(bitmap: Bitmap) {
        if (mrzDetected) return
        
        progressBar.visibility = View.VISIBLE
        statusTextView.text = "Processing image..."
        
        lifecycleScope.launch {
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                
                val task = recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val mrzText = visionText.text
                        Log.d(TAG, "OCR result: $mrzText")
                        
                        if (mrzText.isNotEmpty()) {
                            val can = mrzParser.extractCAN(mrzText, documentType)
                            if (can.isNotEmpty() && mrzParser.isValidCAN(can)) {
                                mrzDetected = true
                                statusTextView.text = "✓ Document recognized! CAN: $can"
                                statusTextView.setTextColor(android.graphics.Color.GREEN)
                                
                                // Auto-proceed to NFC after 1 second
                                lifecycleScope.launch {
                                    delay(1000)
                                    proceedToNFC(can)
                                }
                            }
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "OCR failed: ${exception.message}")
                        statusTextView.text = "✗ OCR failed"
                        statusTextView.setTextColor(android.graphics.Color.RED)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame: ${e.message}")
            }
        }
    }
    
    private fun proceedToNFC(can: String) {
        val intent = Intent(this, NFCProgressActivity::class.java)
        intent.putExtra("can", can)
        intent.putExtra("documentType", documentType)
        startActivity(intent)
        finish()
    }
}
```

**NOTE**: This is skeleton. Full Camera2 implementation with frame capture loop will be added in actual rebuild.

---

## NFCProgressActivity.kt - NFC Handling Rewrite

**Current state**: Uses intent filters (unreliable)  
**New state**: Uses NfcAdapter.enableReaderMode() (direct callbacks)

```kotlin
package com.trustnet.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NFCProgressActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    
    companion object {
        private const val TAG = "NFCProgressActivity"
    }
    
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var statusTextView: TextView
    private var extractedCAN: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_progress)
        
        // Get CAN from camera activity
        extractedCAN = intent.getStringExtra("can") ?: ""
        
        statusTextView = findViewById(R.id.statusTextView)
        statusTextView.text = "Ready to scan NFC\nCAN: $extractedCAN"
        
        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not supported", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Enable reader mode with direct callback
        nfcAdapter.enableReaderMode(
            this,
            this,  // ReaderCallback
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or 
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter.disableReaderMode(this)
    }
    
    /**
     * Direct NFC tag callback - called when tag detected
     * No intents, no system dialogs, app has full control
     */
    override fun onTagDiscovered(tag: Tag) {
        Log.d(TAG, "NFC tag discovered!")
        
        runOnUiThread {
            statusTextView.text = "Reading NFC chip..."
        }
        
        // Perform NFC reading in background
        GlobalScope.launch {
            try {
                // Use extracted CAN for BAC authentication
                val nfcReader = GovernmentIDNFCReader()
                val result = nfcReader.readFromTag(tag, extractedCAN)
                
                if (result != null) {
                    // Convert to Bundle for passing to MainActivity
                    val bundle = Bundle()
                    bundle.putString("firstName", result.firstName)
                    bundle.putString("lastName", result.lastName)
                    bundle.putString("documentNumber", result.documentNumber)
                    bundle.putString("dateOfBirth", result.dateOfBirth)
                    bundle.putString("gender", result.gender)
                    bundle.putString("nationality", result.nationality)
                    bundle.putString("dateOfExpiry", result.dateOfExpiry)
                    
                    // Pass results to MainActivity
                    runOnUiThread {
                        val intent = android.content.Intent(this@NFCProgressActivity, MainActivity::class.java)
                        intent.putExtra("nfcResultData", bundle)
                        startActivity(intent)
                        finish()
                    }
                } else {
                    runOnUiThread {
                        statusTextView.text = "✗ NFC read failed (PACE auth failed)"
                        Toast.makeText(this@NFCProgressActivity, "Failed to read NFC chip", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "NFC reading error: ${e.message}")
                runOnUiThread {
                    statusTextView.text = "✗ Error: ${e.message}"
                }
            }
        }
    }
}
```

---

## BACKeyService.kt - NEW FILE (BAC Key Derivation)

```kotlin
package com.trustnet.app

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

class BACKeyService {
    
    /**
     * Derive BAC (Basic Access Control) key from MRZ data
     * 
     * Formula:
     * 1. Concatenate: documentNumber + dateOfBirth + dateOfExpiry
     * 2. SHA-1 hash
     * 3. Derive Kenc and Kmac from Kseed
     */
    fun deriveBACKey(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String
    ): ByteArray {
        // Concatenate MRZ components
        val mrzData = documentNumber + dateOfBirth + dateOfExpiry
        
        // SHA-1 hash
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val kseed = messageDigest.digest(mrzData.toByteArray())
        
        // In real BAC, would derive Kenc and Kmac from Kseed
        // For now, return Kseed as the key
        return kseed
    }
    
    /**
     * Verify BAC key format is correct (20 bytes for SHA-1)
     */
    fun isValidBACKey(key: ByteArray): Boolean {
        return key.size == 20  // SHA-1 produces 20 bytes
    }
}
```

---

## NO DELETIONS - These Stay Exactly As They Are

```
✓ MRZParser.kt - NOT TOUCHING
✓ GovernmentIDNFCReader.kt - NOT TOUCHING
✓ PACEAuthenticator.kt - NOT TOUCHING
✓ MainActivity.kt - MINIMAL CHANGES ONLY
✓ DocumentTypeActivity.kt - NO CHANGES
✓ SplashActivity.kt - NO CHANGES
✓ All layout files except activity_camera.xml - NO CHANGES
✓ All string resources - NO CHANGES
✓ All color resources - NO CHANGES
✓ All themes - NO CHANGES
```

---

## Summary of Changes

| File | Change Type | Details |
| ---- | ----------- | ------- |
| CameraActivity.kt | REWRITE | Replace Intent camera with Camera2 embedded |
| NFCProgressActivity.kt | REWRITE | Replace intent filters with enableReaderMode() |
| BACKeyService.kt | CREATE | New file for BAC key derivation |
| activity_camera.xml | ADD ELEMENT | Add SurfaceView |
| build.gradle | ADD DEPS | Add Camera2 dependencies |
| AndroidManifest.xml | REMOVE | Remove NFC intent filters |

**Total files modified: 6**  
**Total files deleted: 0**  
**Total files preserved: 50+**  
**Branding preserved: 100%**

---

**Status**: Specification complete and documented. Ready to implement.
