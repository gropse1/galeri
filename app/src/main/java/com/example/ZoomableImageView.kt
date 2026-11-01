package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val currentMatrix = Matrix()
    private val savedMatrix = Matrix()

    private val minScale = 1.0f
    private val maxScale = 5.0f

    private val lastTouch = PointF()
    private val startTouch = PointF()
    private var mode = NONE

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImageToView()
    }

    fun resetZoom() {
        currentMatrix.reset()
        fitImageToView()
    }

    private fun fitImageToView() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val drawableWidth = d.intrinsicWidth.toFloat()
        val drawableHeight = d.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) return

        val scaleX = viewWidth / drawableWidth
        val scaleY = viewHeight / drawableHeight
        val scale = minOf(scaleX, scaleY)

        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        currentMatrix.reset()
        currentMatrix.postScale(scale, scale)
        currentMatrix.postTranslate(dx, dy)
        imageMatrix = currentMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val currentScale = getCurrentScale()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(currentMatrix)
                startTouch.set(event.x, event.y)
                lastTouch.set(event.x, event.y)
                mode = DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = ZOOM
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && currentScale > minScale * 1.05f) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    currentMatrix.postTranslate(dx, dy)
                    clampTranslation()
                    imageMatrix = currentMatrix
                    lastTouch.set(event.x, event.y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (currentScale < minScale) {
                    fitImageToView()
                } else {
                    clampTranslation()
                    imageMatrix = currentMatrix
                }
            }
        }

        return true
    }

    private fun getCurrentScale(): Float {
        currentMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun clampTranslation() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        currentMatrix.mapRect(rect)

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) {
                deltaX = -rect.left
            } else if (rect.right < viewWidth) {
                deltaX = viewWidth - rect.right
            }
        }

        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) {
                deltaY = -rect.top
            } else if (rect.bottom < viewHeight) {
                deltaY = viewHeight - rect.bottom
            }
        }

        currentMatrix.postTranslate(deltaX, deltaY)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val current = getCurrentScale()
            val newScale = current * scaleFactor

            if (newScale in minScale..maxScale) {
                currentMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                clampTranslation()
                imageMatrix = currentMatrix
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val currentScale = getCurrentScale()
            if (currentScale > minScale * 1.5f) {
                fitImageToView()
            } else {
                val targetScale = 2.5f / currentScale
                currentMatrix.postScale(targetScale, targetScale, e.x, e.y)
                clampTranslation()
                imageMatrix = currentMatrix
            }
            return true
        }
    }
}
