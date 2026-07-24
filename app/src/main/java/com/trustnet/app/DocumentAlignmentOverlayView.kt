package com.trustnet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Overlay view that displays document alignment guides similar to ReadID Me
 * Shows a frame where the user should position the document
 * Provides visual feedback on document alignment quality
 */
class DocumentAlignmentOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val framePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val cornerFillPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val instructionPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 14f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val shadowPaint = Paint().apply {
        color = Color.parseColor("#7F000000")  // Semi-transparent black
        style = Paint.Style.FILL
    }

    // Alignment state
    var alignmentQuality: AlignmentQuality = AlignmentQuality.NOT_DETECTED
    var instruction: String = "Position document in frame"
    var isDocumentDetected: Boolean = false

    enum class AlignmentQuality {
        NOT_DETECTED,        // Document not found
        MISALIGNED,          // Document found but not well aligned
        PARTIALLY_ALIGNED,   // Document partially aligned
        WELL_ALIGNED,        // Document is well aligned and ready
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Draw dark background (vignette effect on corners)
        drawVignette(canvas, width, height)

        // Calculate frame dimensions (golden ratio for ID documents: ~1.6:1)
        val frameWidth = width * 0.85f
        val frameHeight = frameWidth / 1.6f
        val frameLeft = (width - frameWidth) / 2f
        val frameTop = (height - frameHeight) / 2f
        val frameRight = frameLeft + frameWidth
        val frameBottom = frameTop + frameHeight

        val frameRect = RectF(frameLeft, frameTop, frameRight, frameBottom)

        // Draw frame with corner indicators
        drawAlignmentFrame(canvas, frameRect)

        // Draw positioning guides
        drawPositioningGuides(canvas, frameRect)

        // Draw instructions
        drawInstructions(canvas, width, height, frameRect)
    }

    private fun drawVignette(canvas: Canvas, width: Float, height: Float) {
        // Draw dark overlay on corners to guide user toward center
        val margin = 30f

        // Top shadow
        canvas.drawRect(0f, 0f, width, margin, shadowPaint)
        // Bottom shadow
        canvas.drawRect(0f, height - margin, width, height, shadowPaint)
        // Left shadow
        canvas.drawRect(0f, margin, margin, height - margin, shadowPaint)
        // Right shadow
        canvas.drawRect(width - margin, margin, width, height - margin, shadowPaint)
    }

    private fun drawAlignmentFrame(canvas: Canvas, frameRect: RectF) {
        // Determine color based on alignment quality
        val frameColor = when (alignmentQuality) {
            AlignmentQuality.NOT_DETECTED -> Color.parseColor("#FFFF00")       // Yellow
            AlignmentQuality.MISALIGNED -> Color.parseColor("#FF6600")         // Orange
            AlignmentQuality.PARTIALLY_ALIGNED -> Color.parseColor("#FFCC00")  // Light orange
            AlignmentQuality.WELL_ALIGNED -> Color.parseColor("#00FF00")       // Green
        }

        framePaint.color = frameColor
        cornerPaint.color = frameColor
        cornerFillPaint.color = frameColor

        // Draw main frame rectangle
        canvas.drawRect(frameRect, framePaint)

        // Draw corner squares for visual guidance
        val cornerSize = 40f
        drawCorner(canvas, frameRect.left, frameRect.top, -1f, -1f, cornerSize)      // Top-left
        drawCorner(canvas, frameRect.right, frameRect.top, 1f, -1f, cornerSize)       // Top-right
        drawCorner(canvas, frameRect.left, frameRect.bottom, -1f, 1f, cornerSize)     // Bottom-left
        drawCorner(canvas, frameRect.right, frameRect.bottom, 1f, 1f, cornerSize)     // Bottom-right

        // Draw center crosshair
        val centerX = frameRect.centerX()
        val centerY = frameRect.centerY()
        val crossSize = 20f
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, framePaint)
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, framePaint)
    }

    private fun drawCorner(
        canvas: Canvas,
        x: Float,
        y: Float,
        dirX: Float,
        dirY: Float,
        size: Float
    ) {
        val offset = 10f
        val thickness = 4f

        // Horizontal line
        val hStartX = x + (dirX * offset)
        val hEndX = hStartX + (dirX * size)
        canvas.drawLine(hStartX, y, hEndX, y, cornerPaint)

        // Vertical line
        val vStartY = y + (dirY * offset)
        val vEndY = vStartY + (dirY * size)
        canvas.drawLine(x, vStartY, x, vEndY, cornerPaint)

        // Corner dot
        canvas.drawCircle(x, y, 6f, cornerFillPaint)
    }

    private fun drawPositioningGuides(canvas: Canvas, frameRect: RectF) {
        val guidePaint = Paint().apply {
            color = Color.parseColor("#80FFFFFF")  // Semi-transparent white
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // Horizontal guide lines (thirds)
        val hThird = (frameRect.height()) / 3f
        canvas.drawLine(
            frameRect.left,
            frameRect.top + hThird,
            frameRect.right,
            frameRect.top + hThird,
            guidePaint
        )
        canvas.drawLine(
            frameRect.left,
            frameRect.top + (2 * hThird),
            frameRect.right,
            frameRect.top + (2 * hThird),
            guidePaint
        )

        // Vertical guide lines (thirds)
        val vThird = (frameRect.width()) / 3f
        canvas.drawLine(
            frameRect.left + vThird,
            frameRect.top,
            frameRect.left + vThird,
            frameRect.bottom,
            guidePaint
        )
        canvas.drawLine(
            frameRect.left + (2 * vThird),
            frameRect.top,
            frameRect.left + (2 * vThird),
            frameRect.bottom,
            guidePaint
        )
    }

    private fun drawInstructions(canvas: Canvas, width: Float, height: Float, frameRect: RectF) {
        // Primary instruction text (above frame)
        val instructionY = frameRect.top - 40f
        canvas.drawText(instruction, width / 2f, instructionY, instructionPaint)

        // Status text (below frame)
        val statusText = when (alignmentQuality) {
            AlignmentQuality.NOT_DETECTED -> "Position document in frame"
            AlignmentQuality.MISALIGNED -> "Adjust document alignment"
            AlignmentQuality.PARTIALLY_ALIGNED -> "Almost there..."
            AlignmentQuality.WELL_ALIGNED -> "✓ Perfect! Ready to scan"
        }
        val statusY = frameRect.bottom + 50f
        canvas.drawText(statusText, width / 2f, statusY, instructionPaint)

        // Additional tips at bottom
        val tipsY = frameRect.bottom + 90f
        val tipsPaint = Paint().apply {
            color = Color.parseColor("#CCCCCC")
            textSize = 12f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Ensure MRZ lines are clearly visible", width / 2f, tipsY, tipsPaint)
    }

    /**
     * Update alignment state based on detected document
     */
    fun updateAlignment(quality: AlignmentQuality) {
        alignmentQuality = quality
        invalidate()
    }

    /**
     * Set document detection state
     */
    fun setDocumentDetected(detected: Boolean, quality: AlignmentQuality = AlignmentQuality.WELL_ALIGNED) {
        isDocumentDetected = detected
        if (detected) {
            alignmentQuality = quality
        }
        invalidate()
    }
}
