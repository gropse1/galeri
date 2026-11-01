package com.example

import android.app.Activity
import android.app.Dialog
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.InputStream
import java.util.concurrent.Executors

class FullscreenViewerDialog(
    private val activity: Activity,
    private val contentResolver: ContentResolver,
    private val item: MediaItem,
    private val onActionClick: (MediaItem) -> Unit
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(androidSavedInstanceState: android.os.Bundle?) {
        super.onCreate(androidSavedInstanceState)

        val rootLayout = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Center Zoomable Image View
        val zoomableImageView = ZoomableImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(zoomableImageView)

        // Loading spinner
        val progressBar = ProgressBar(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.VISIBLE
        }
        rootLayout.addView(progressBar)

        // Top Control Bar (Translucent dark overlay)
        val density = activity.resources.displayMetrics.density
        val topBarHeight = (64 * density).toInt()

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                topBarHeight
            ).apply {
                gravity = Gravity.TOP
            }
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }

        // Close Button
        val closeButton = TextView(activity).apply {
            val btnSize = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            gravity = Gravity.CENTER
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#33FFFFFF"))
            }
            background = bg
            setOnClickListener {
                dismiss()
            }
        }
        topBar.addView(closeButton)

        // Metadata Title & Subtitle
        val textContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
                marginEnd = (12 * density).toInt()
            }
        }

        val titleText = TextView(activity).apply {
            text = item.displayName
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        textContainer.addView(titleText)

        val metaText = TextView(activity).apply {
            text = "${item.getFormattedDate()}  •  ${item.getFormattedSize()}"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 11f
        }
        textContainer.addView(metaText)

        topBar.addView(textContainer)

        // Action Button ("Arşivle" or "Arşivden Çıkar")
        val actionButton = Button(activity).apply {
            val isArchiveMode = item.isArchive
            text = if (isArchiveMode) "Arşivden Çıkar" else "Arşivle"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val bgColor = if (isArchiveMode) Color.parseColor("#0288D1") else Color.parseColor("#2E7D32")
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(bgColor)
            }
            background = bg
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            setOnClickListener {
                dismiss()
                onActionClick(item)
            }
        }
        topBar.addView(actionButton)

        rootLayout.addView(topBar)

        setContentView(rootLayout)

        // Load high-resolution image in background safely
        loadHighResImage(zoomableImageView, progressBar)
    }

    private fun loadHighResImage(imageView: ZoomableImageView, progressBar: ProgressBar) {
        val displayMetrics = activity.resources.displayMetrics
        val targetWidth = displayMetrics.widthPixels
        val targetHeight = displayMetrics.heightPixels

        executor.execute {
            var bitmap: Bitmap? = null
            try {
                if (item.isArchive && item.archiveFile != null) {
                    val filePath = item.archiveFile.absolutePath
                    val boundsOptions = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(filePath, boundsOptions)

                    val sampleSize = calculateInSampleSize(boundsOptions, targetWidth, targetHeight)
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    bitmap = BitmapFactory.decodeFile(filePath, decodeOptions)
                } else {
                    val boundsOptions = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    openInputStream(item.uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, boundsOptions)
                    }

                    val sampleSize = calculateInSampleSize(boundsOptions, targetWidth, targetHeight)
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    openInputStream(item.uri)?.use { stream ->
                        bitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
                    }
                }
            } catch (e: Exception) {
                // If decoding fails, bitmap is null
            }

            mainHandler.post {
                progressBar.visibility = View.GONE
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream? {
        return try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return maxOf(1, inSampleSize)
    }
}
