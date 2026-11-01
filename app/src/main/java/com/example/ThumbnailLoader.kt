package com.example

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

object ThumbnailLoader {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun load(
        contentResolver: ContentResolver,
        item: MediaItem,
        imageView: ImageView,
        placeholderBitmap: Bitmap? = null
    ) {
        val key = item.uniqueKey
        imageView.tag = key

        val cachedBitmap = memoryCache.get(key)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            return
        }

        if (placeholderBitmap != null) {
            imageView.setImageBitmap(placeholderBitmap)
        } else {
            imageView.setImageDrawable(null)
        }

        executor.execute {
            var loadedBitmap: Bitmap? = null
            try {
                if (item.isArchive && item.archiveFile != null) {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4
                    }
                    loadedBitmap = BitmapFactory.decodeFile(item.archiveFile.absolutePath, options)
                } else {
                    loadedBitmap = contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
                }
            } catch (e: Exception) {
                // If loadThumbnail fails (e.g. corrupted header), loadedBitmap remains null
            }

            if (loadedBitmap != null) {
                memoryCache.put(key, loadedBitmap)
                mainHandler.post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(loadedBitmap)
                    }
                }
            }
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
    }
}
