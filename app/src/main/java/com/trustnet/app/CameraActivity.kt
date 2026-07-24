package com.trustnet.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera activity with embedded camera preview and auto-capture
 * Uses CameraX API for reliable camera handling across devices
 * Auto-captures when MRZ is detected in frame
 */
class CameraActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "CameraActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
    }
    
    private lateinit var previewView: PreviewView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var alignmentOverlay: DocumentAlignmentOverlayView
    private lateinit var mrzParser: MRZParser
    private lateinit var cameraExecutor: ExecutorService
    
    private var documentType: String = ""
    private var imageCapture: ImageCapture? = null
    private var mrzDetected = false  // Flag to prevent duplicate captures
    private var mrzConfidenceScore = 0f  // Track MRZ detection confidence for alignment
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        
        // Initialize ML Kit text recognizer
        mrzParser = MRZParser()
        
        // Get document type from intent
        documentType = intent.getStringExtra(DocumentTypeActivity.DOCUMENT_TYPE_KEY) ?: "ID"
        
        // Get view references
        previewView = findViewById(R.id.previewView)
        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        alignmentOverlay = findViewById(R.id.alignmentOverlay)
        
        val titleTextView: TextView = findViewById(R.id.titleTextView)
        titleTextView.text = "Capture $documentType Document"
        
        // Initialize executor for camera operations
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Request camera permission and start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    
    /**
     * Initialize and start camera with CameraX
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                
                // Preview use case - display to PreviewView
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                
                // ImageCapture use case - capture frames for OCR
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                try {
                    // Unbind any previous bindings
                    cameraProvider.unbindAll()
                    
                    // Bind preview and imageCapture to lifecycle
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                    )
                    
                    statusTextView.text = "Position document in frame..."
                    statusTextView.setTextColor(Color.BLACK)
                    
                    // Start continuous frame capture loop for OCR
                    startContinuousFrameCapture()
                    
                } catch (exc: Exception) {
                    Log.e(TAG, "Camera binding failed", exc)
                    statusTextView.text = "Camera binding failed: ${exc.message}"
                    statusTextView.setTextColor(Color.RED)
                }
                
            } catch (exc: Exception) {
                Log.e(TAG, "Camera provider failed", exc)
                statusTextView.text = "Camera initialization failed: ${exc.message}"
                statusTextView.setTextColor(Color.RED)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    /**
     * Capture frames continuously and perform OCR detection
     * Auto-captures when MRZ is detected
     */
    private fun startContinuousFrameCapture() {
        val captureInterval = 2000L  // Capture every 2 seconds
        
        lifecycleScope.launch {
            while (true) {
                if (!mrzDetected) {
                    captureAndProcessFrame()
                }
                delay(captureInterval)
            }
        }
    }
    
    /**
     * Capture current frame and process with ML Kit OCR
     */
    private fun captureAndProcessFrame() {
        val imageCapture = imageCapture ?: return
        
        // Create file for captured frame
        val photoFile = createPhotoFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Frame captured: ${output.savedUri}")
                    
                    // Load bitmap and process with OCR
                    val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
                    if (bitmap != null) {
                        processBitmapForMRZ(bitmap)
                        photoFile.delete()  // Clean up temp file
                    }
                }
                
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Frame capture failed: ${exc.message}")
                }
            }
        )
    }
    
    /**
     * Process bitmap with ML Kit OCR to detect MRZ
     */
    private fun processBitmapForMRZ(bitmap: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Assess document alignment quality
                    val alignmentQuality = assessAlignmentQuality(visionText)
                    updateAlignmentFeedback(alignmentQuality)
                    
                    // Check if MRZ detected in the text
                    val fullOCRText = visionText.text
                    Log.d(TAG, "Full OCR result (${fullOCRText.lines().size} lines, ${fullOCRText.length} chars, alignment=$alignmentQuality)")
                    
                    // Extract only the actual MRZ lines (not all text on the card)
                    // MRZ is 2-3 lines depending on document type
                    val mrzText = mrzParser.extractMRZLines(fullOCRText)
                    
                    if (mrzText.isEmpty()) {
                        Log.w(TAG, "MRZ lines not clearly detected yet")
                        return@addOnSuccessListener
                    }
                    
                    if (mrzText.isNotEmpty()) {
                        // Document detected - proceed to confirmation screen
                        // Don't parse OCR MRZ - it's unreliable. User confirms, then read from chip.
                        mrzDetected = true
                        
                        // Update overlay to show detection complete
                        alignmentOverlay.setDocumentDetected(true, DocumentAlignmentOverlayView.AlignmentQuality.WELL_ALIGNED)
                        
                        statusTextView.text = "✓ Document captured!\nReview information..."
                        statusTextView.setTextColor(Color.GREEN)
                        progressBar.visibility = View.VISIBLE
                        
                        // Launch confirmation screen for user to verify MRZ data
                        lifecycleScope.launch {
                            delay(1500)
                            
                            // Parse all MRZ lines (2-3 lines depending on document)
                            val mrzLines = mrzText.split("\n")
                            val mrzLine1 = if (mrzLines.size > 0) mrzLines[0] else ""
                            val mrzLine2 = if (mrzLines.size > 1) mrzLines[1] else ""
                            val mrzLine3 = if (mrzLines.size > 2) mrzLines[2] else ""
                            
                            val confirmIntent = Intent(this@CameraActivity, ConfirmationActivity::class.java)
                            confirmIntent.putExtra("DOCUMENT_TYPE", documentType)
                            confirmIntent.putExtra("MRZ_LINE_1", mrzLine1)
                            confirmIntent.putExtra("MRZ_LINE_2", mrzLine2)
                            confirmIntent.putExtra("MRZ_LINE_3", mrzLine3)
                            
                            startActivity(confirmIntent)
                            finish()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR processing failed: ${e.message}")
                }
                .addOnCompleteListener {
                    recognizer.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in OCR: ${e.message}")
        }
    }
    
    /**
     * Extract CAN from MRZ - CAN is the DOB (Date of Birth) in YYMMDD format
     * Located at positions 14-19 in MRZ line 2
     * For Spanish DNI/EU IDs: DOB from MRZ IS the CAN
     * e.g., DOB "31 10 1965" → CAN "651031" (YYMMDD format: 65-10-31)
     * 
     * MRZ Line 2 structure (44 chars):
     * Positions 1-9: Document number
     * Position 10: Check digit
     * Positions 11-13: Country code or filler
     * Positions 14-19: DOB (YYMMDD) ← THIS IS THE CAN
     * Position 20: DOB check digit
     * Positions 21-26: Expiry (YYMMDD)
     * Etc.
     */
    private fun extractCANFromVisualData(fullOCRText: String): String {
        Log.d(TAG, "CAN extraction: Looking for DOB (positions 14-19, YYMMDD) in MRZ line 2")
        
        val lines = fullOCRText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        // Attempt 1: Look for complete MRZ lines (44 chars or close)
        for ((idx, line) in lines.withIndex()) {
            val cleanLine = line.replace(" ", "")  // Remove OCR spacing artifacts
            
            Log.d(TAG, "Line $idx (len=${cleanLine.length}): '$cleanLine'")
            
            // MRZ lines are 44 characters; accept 40+ to tolerate OCR truncation
            if (cleanLine.length >= 40 && cleanLine.matches(Regex("[A-Z0-9<]+"))) {
                // Extract DOB from positions 13-19 (0-indexed for YYMMDD)
                try {
                    val can = cleanLine.substring(13, 19)  // Positions 14-19 (1-indexed) = indices 13-19 (0-indexed)
                    if (can.matches(Regex("[0-9]{6}"))) {  // Must be exactly 6 digits
                        Log.d(TAG, "✓ CAN found in line $idx: '$can' (DOB=${can.substring(0, 2)}-${can.substring(2, 4)}-${can.substring(4, 6)})")
                        return can
                    } else {
                        Log.d(TAG, "Line $idx has candidate at pos 13-19 but not numeric: '$can'")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Substring extraction failed for line $idx: ${e.message}")
                }
            }
        }
        
        // Attempt 2: Concatenate all alphanumeric content and search for 6-digit blocks
        // (handles cases where MRZ is split across multiple lines by OCR)
        Log.d(TAG, "Attempt 2: Searching for 6-digit blocks that could be DOB/CAN")
        val allContent = lines.joinToString("")
            .replace(" ", "")
            .replace("<", "")  // Remove filler characters
            .filter { it.isLetterOrDigit() }
        
        // Look for 6-digit sequences that appear to be YYMMDD
        val digitRegex = Regex("(\\d{6})")
        val matches = digitRegex.findAll(allContent)
        for (match in matches) {
            val candidate = match.value
            val yy = candidate.substring(0, 2).toInt()
            val mm = candidate.substring(2, 4).toInt()
            val dd = candidate.substring(4, 6).toInt()
            
            // Validate as plausible date: MM 01-12, DD 01-31
            if (mm in 1..12 && dd in 1..31) {
                Log.d(TAG, "✓ CAN found (search): '$candidate' (valid date: ${yy.toString().padStart(2, '0')}-${mm.toString().padStart(2, '0')}-${dd.toString().padStart(2, '0')})")
                return candidate
            }
        }
        
        Log.w(TAG, "CAN not found in MRZ - will proceed with empty string")
        return ""
    }
    
    /**
     * Create temporary photo file for frame capture
     */
    private fun createPhotoFile(): File {
        val storageDir: File? = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        return File.createTempFile("trustnet_frame_$timestamp", ".jpg", storageDir)
    }
    
    /**
     * Save captured bitmap to file
     */
    private fun saveBitmapToFile(bitmap: Bitmap): String {
        try {
            val storageDir: File? = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            if (storageDir != null && !storageDir.exists()) {
                storageDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val imageFile = File(storageDir, "trustnet_capture_$timestamp.jpg")
            
            imageFile.outputStream().use { outputStream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)
            }
            
            Log.d(TAG, "Bitmap saved to: ${imageFile.absolutePath}")
            return imageFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
            return ""
        }
    }
    
    /**
     * Analyze detected text to assess document alignment quality
     */
    private fun assessAlignmentQuality(visionText: com.google.mlkit.vision.text.Text): DocumentAlignmentOverlayView.AlignmentQuality {
        // Analyze text blocks for alignment characteristics
        val textBlocks = visionText.textBlocks
        
        if (textBlocks.isEmpty()) {
            return DocumentAlignmentOverlayView.AlignmentQuality.NOT_DETECTED
        }
        
        // Calculate text block distribution (better alignment = more centered, distributed blocks)
        var totalBlockWidth = 0f
        var totalBlockHeight = 0f
        var blockCount = 0
        var maxBlockArea = 0f
        
        for (block in textBlocks) {
            val boundingBox = block.boundingBox
            if (boundingBox != null) {
                val width = boundingBox.width().toFloat()
                val height = boundingBox.height().toFloat()
                val area = width * height
                
                totalBlockWidth += width
                totalBlockHeight += height
                maxBlockArea = maxOf(maxBlockArea, area)
                blockCount++
            }
        }
        
        if (blockCount == 0) {
            return DocumentAlignmentOverlayView.AlignmentQuality.NOT_DETECTED
        }
        
        val avgBlockWidth = totalBlockWidth / blockCount
        val avgBlockHeight = totalBlockHeight / blockCount
        
        // Heuristics for alignment quality:
        // - Good MRZ detection = multiple medium-to-large text blocks
        // - Poor alignment = very small or very large blocks (tilted/skewed)
        // - Ideal = blocks fill 30-70% of average view area
        
        val largeBlockCount = textBlocks.count { 
            val bb = it.boundingBox ?: return@count false
            val area = (bb.width().toFloat() * bb.height().toFloat())
            area > (maxBlockArea * 0.3f)  // Count blocks that are at least 30% of the largest
        }
        
        // Score based on text distribution
        val alignmentScore = when {
            largeBlockCount >= 3 && avgBlockHeight > 20 -> 100f  // Excellent
            largeBlockCount >= 2 && avgBlockHeight > 15 -> 75f   // Good
            largeBlockCount >= 1 && avgBlockHeight > 10 -> 50f   // Partial
            else -> 25f                                           // Poor
        }
        
        // Update confidence score for MRZ detection
        mrzConfidenceScore = alignmentScore
        
        return when {
            alignmentScore >= 75f -> DocumentAlignmentOverlayView.AlignmentQuality.WELL_ALIGNED
            alignmentScore >= 50f -> DocumentAlignmentOverlayView.AlignmentQuality.PARTIALLY_ALIGNED
            alignmentScore >= 25f -> DocumentAlignmentOverlayView.AlignmentQuality.MISALIGNED
            else -> DocumentAlignmentOverlayView.AlignmentQuality.NOT_DETECTED
        }
    }
    
    /**
     * Update overlay with current alignment feedback
     */
    private fun updateAlignmentFeedback(quality: DocumentAlignmentOverlayView.AlignmentQuality) {
        val instruction = when (quality) {
            DocumentAlignmentOverlayView.AlignmentQuality.NOT_DETECTED -> {
                "Position document in frame"
            }
            DocumentAlignmentOverlayView.AlignmentQuality.MISALIGNED -> {
                "Adjust document - ensure MRZ is visible"
            }
            DocumentAlignmentOverlayView.AlignmentQuality.PARTIALLY_ALIGNED -> {
                "Almost there... center the document"
            }
            DocumentAlignmentOverlayView.AlignmentQuality.WELL_ALIGNED -> {
                "✓ Perfect alignment!"
            }
        }
        
        alignmentOverlay.updateAlignment(quality)
        alignmentOverlay.instruction = instruction
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

