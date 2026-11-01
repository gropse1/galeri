package com.example

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    enum class ViewMode {
        GALLERY,
        ARCHIVE
    }

    private var currentMode = ViewMode.GALLERY
    private val mediaList = mutableListOf<MediaItem>()
    private lateinit var adapter: GalleryAdapter

    private lateinit var rootLayout: FrameLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateView: LinearLayout
    private lateinit var emptyStateTitle: TextView
    private lateinit var emptyStateDesc: TextView
    private lateinit var permissionView: LinearLayout
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingText: TextView

    // Top Header Views
    private lateinit var topHeaderLayout: LinearLayout
    private lateinit var headerTitleText: TextView
    private lateinit var modeToggleButton: Button

    // Selection Bar Views
    private lateinit var selectionBarLayout: LinearLayout
    private lateinit var selectionCountText: TextView
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button
    private lateinit var primaryActionButton: Button

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks files temporarily created in archive while waiting for user deletion approval
    private var pendingArchiveUris = listOf<Uri>()
    private var pendingCreatedArchiveFiles = listOf<File>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            permissionView.visibility = View.GONE
            loadMedia()
        } else {
            showPermissionNeededUi()
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        hideLoading()
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(
                this,
                "${pendingCreatedArchiveFiles.size} fotoğraf başarıyla arşive taşındı.",
                Toast.LENGTH_SHORT
            ).show()
            pendingArchiveUris = emptyList()
            pendingCreatedArchiveFiles = emptyList()
            adapter.clearSelection()
            loadMedia()
        } else {
            // User cancelled deletion: remove the created archive copies to prevent duplicates
            backgroundExecutor.execute {
                for (file in pendingCreatedArchiveFiles) {
                    if (file.exists()) {
                        file.delete()
                    }
                }
                pendingArchiveUris = emptyList()
                pendingCreatedArchiveFiles = emptyList()
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "Arşivleme iptal edildi (silme onayı verilmedi).",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadMedia()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Native Dark Theme Window setup
        window.statusBarColor = Color.parseColor("#121212")
        window.navigationBarColor = Color.parseColor("#121212")

        buildUi()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasRequiredPermission()) {
            permissionView.visibility = View.GONE
            loadMedia()
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top Header Bar
        val topBarHeight = (58 * density).toInt()
        topHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                topBarHeight
            )
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }

        headerTitleText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "📁 Galeri (0)"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        topHeaderLayout.addView(headerTitleText)

        modeToggleButton = Button(this).apply {
            text = "Görünüm: Galeri"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#2C2C2C"))
                setStroke((1 * density).toInt(), Color.parseColor("#444444"))
            }
            background = bg
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setOnClickListener {
                switchViewMode()
            }
        }
        topHeaderLayout.addView(modeToggleButton)

        mainContainer.addView(topHeaderLayout)

        // 2. Selection Bar (Visible when 1+ photos are selected)
        val selectionBarHeight = (54 * density).toInt()
        selectionBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                selectionBarHeight
            )
            setBackgroundColor(Color.parseColor("#263238"))
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            visibility = View.GONE
        }

        selectionCountText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "0 Seçildi"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        selectionBarLayout.addView(selectionCountText)

        selectAllButton = Button(this).apply {
            text = "Tümü"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * density
                setColor(Color.TRANSPARENT)
            }
            background = bg
            setOnClickListener {
                adapter.selectAll()
            }
        }
        selectionBarLayout.addView(selectAllButton)

        cancelSelectionButton = Button(this).apply {
            text = "İptal"
            setTextColor(Color.parseColor("#EF9A9A"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * density
                setColor(Color.TRANSPARENT)
            }
            background = bg
            setOnClickListener {
                adapter.clearSelection()
            }
        }
        selectionBarLayout.addView(cancelSelectionButton)

        primaryActionButton = Button(this).apply {
            text = "Arşivle"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#2E7D32"))
            }
            background = bg
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            setOnClickListener {
                onPrimaryActionClicked()
            }
        }
        selectionBarLayout.addView(primaryActionButton)

        mainContainer.addView(selectionBarLayout)

        // 3. RecyclerView (3 columns)
        val recyclerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        recyclerView = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            val spacing = (2 * density).toInt()
            addItemDecoration(GridSpacingItemDecoration(3, spacing))
        }

        adapter = GalleryAdapter(
            context = this,
            contentResolver = contentResolver,
            onItemClick = { item ->
                showFullscreenViewer(item)
            },
            onItemLongClick = { item ->
                adapter.toggleSelection(item)
            },
            onSelectionChanged = { count ->
                updateSelectionUi(count)
            }
        )
        recyclerView.adapter = adapter
        recyclerContainer.addView(recyclerView)

        // Empty state view
        emptyStateView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            visibility = View.GONE
        }

        emptyStateTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        emptyStateView.addView(emptyStateTitle)

        emptyStateDesc = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        emptyStateView.addView(emptyStateDesc)

        recyclerContainer.addView(emptyStateView)

        // Permission required view
        permissionView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            visibility = View.GONE
        }

        val permTitle = TextView(this).apply {
            text = "Fotoğraf İzni Gerekli"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        permissionView.addView(permTitle)

        val permDesc = TextView(this).apply {
            text = "Galeri ve Arşiv uygulamasının fotoğraflarınızı güvenli şekilde listeleyip arşivleyebilmesi için depolama izni gereklidir.\n\nUygulama sıfır telemetrilidir ve internet izni bulunmaz."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (20 * density).toInt())
        }
        permissionView.addView(permDesc)

        val grantButton = Button(this).apply {
            text = "İzin Ver"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#1976D2"))
            }
            background = bg
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
            setOnClickListener {
                checkAndRequestPermissions()
            }
        }
        permissionView.addView(grantButton)

        recyclerContainer.addView(permissionView)

        mainContainer.addView(recyclerContainer)
        rootLayout.addView(mainContainer)

        // Loading overlay
        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#B3000000"))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }

        val spinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        loadingOverlay.addView(spinner)

        loadingText = TextView(this).apply {
            text = "İşlem yapılıyor..."
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        loadingOverlay.addView(loadingText)

        rootLayout.addView(loadingOverlay)

        setContentView(rootLayout)
    }

    private fun showLoading(message: String) {
        loadingText.text = message
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    private fun hasRequiredPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasRequiredPermission()) {
            permissionView.visibility = View.GONE
            loadMedia()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions)
        }
    }

    private fun showPermissionNeededUi() {
        permissionView.visibility = View.VISIBLE
        emptyStateView.visibility = View.GONE
    }

    private fun switchViewMode() {
        adapter.clearSelection()
        currentMode = if (currentMode == ViewMode.GALLERY) ViewMode.ARCHIVE else ViewMode.GALLERY
        updateModeUi()
        loadMedia()
    }

    private fun updateModeUi() {
        val density = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
        }

        if (currentMode == ViewMode.GALLERY) {
            modeToggleButton.text = "Görünüm: Galeri"
            bg.setColor(Color.parseColor("#1B5E20"))
            bg.setStroke((1 * density).toInt(), Color.parseColor("#4CAF50"))
            primaryActionButton.text = "Arşivle"
            val actBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#2E7D32"))
            }
            primaryActionButton.background = actBg
        } else {
            modeToggleButton.text = "Görünüm: Arşiv"
            bg.setColor(Color.parseColor("#0D47A1"))
            bg.setStroke((1 * density).toInt(), Color.parseColor("#42A5F5"))
            primaryActionButton.text = "Arşivden Çıkar"
            val actBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#0288D1"))
            }
            primaryActionButton.background = actBg
        }
        modeToggleButton.background = bg
    }

    private fun updateSelectionUi(count: Int) {
        if (count > 0) {
            selectionBarLayout.visibility = View.VISIBLE
            selectionCountText.text = "$count Seçildi"
            if (currentMode == ViewMode.GALLERY) {
                primaryActionButton.text = "Arşivle ($count)"
            } else {
                primaryActionButton.text = "Arşivden Çıkar ($count)"
            }
        } else {
            selectionBarLayout.visibility = View.GONE
        }
    }

    private fun loadMedia() {
        showLoading("Fotoğraflar yükleniyor...")
        backgroundExecutor.execute {
            val items = if (currentMode == ViewMode.GALLERY) {
                queryGalleryPhotos()
            } else {
                queryArchivePhotos()
            }

            mainHandler.post {
                hideLoading()
                mediaList.clear()
                mediaList.addAll(items)
                adapter.submitList(items)

                val count = items.size
                if (currentMode == ViewMode.GALLERY) {
                    headerTitleText.text = "📁 Galeri ($count)"
                } else {
                    headerTitleText.text = "🔒 Özel Arşiv ($count)"
                }

                if (items.isEmpty()) {
                    emptyStateView.visibility = View.VISIBLE
                    if (currentMode == ViewMode.GALLERY) {
                        emptyStateTitle.text = "Galeride Fotoğraf Bulunamadı"
                        emptyStateDesc.text = "Cihazınızın galerisinde henüz görsel bulunmuyor."
                    } else {
                        emptyStateTitle.text = "Özel Arşiv Boş"
                        emptyStateDesc.text = "Galerideki fotoğraflara uzun basıp seçerek 'Arşivle' butonu ile buraya taşıyabilirsiniz."
                    }
                } else {
                    emptyStateView.visibility = View.GONE
                }
            }
        }
    }

    private fun queryGalleryPhotos(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "IMG_$id.jpg"
                    val dateAdded = cursor.getLong(dateColumn)
                    val size = cursor.getLong(sizeColumn)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    result.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            dateAdded = dateAdded,
                            size = size,
                            archiveFile = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ContentResolver query failed
        }
        return result
    }

    private fun queryArchivePhotos(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val archiveDir = File(filesDir, "archive")
        if (!archiveDir.exists()) {
            archiveDir.mkdirs()
        }

        val files = archiveDir.listFiles { file ->
            file.isFile && !file.name.startsWith(".")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        for ((index, file) in files.withIndex()) {
            val displayName = file.name.substringAfter("_", file.name)
            result.add(
                MediaItem(
                    id = index.toLong(),
                    uri = Uri.fromFile(file),
                    displayName = displayName,
                    dateAdded = file.lastModified() / 1000L,
                    size = file.length(),
                    archiveFile = file
                )
            )
        }
        return result
    }

    private fun onPrimaryActionClicked() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return

        if (currentMode == ViewMode.GALLERY) {
            archiveSelectedPhotos(selected)
        } else {
            restoreSelectedPhotos(selected)
        }
    }

    /**
     * Architectural Requirement:
     * 1) Read selected photos using ContentResolver.openInputStream(uri)
     *    and copy to context.filesDir/archive with unique filenames.
     * 2) Call MediaStore.createDeleteRequest(contentResolver, uriList) (Scoped Storage API 30+).
     * 3) Capture user deletion approval via ActivityResultContracts.StartIntentSenderForResult().
     */
    private fun archiveSelectedPhotos(selected: List<MediaItem>) {
        showLoading("Fotoğraflar arşive kopyalanıyor...")

        backgroundExecutor.execute {
            val archiveDir = File(filesDir, "archive")
            if (!archiveDir.exists()) {
                archiveDir.mkdirs()
            }

            val createdFiles = mutableListOf<File>()
            val urisToDelete = mutableListOf<Uri>()

            for (item in selected) {
                val cleanName = item.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val uniqueFileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_$cleanName"
                val destFile = File(archiveDir, uniqueFileName)

                try {
                    contentResolver.openInputStream(item.uri)?.use { inputStream ->
                        FileOutputStream(destFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    if (destFile.exists() && destFile.length() > 0) {
                        createdFiles.add(destFile)
                        urisToDelete.add(item.uri)
                    } else {
                        destFile.delete()
                    }
                } catch (e: Exception) {
                    destFile.delete()
                }
            }

            mainHandler.post {
                hideLoading()
                if (urisToDelete.isEmpty()) {
                    Toast.makeText(this, "Fotoğraflar kopyalanamadı.", Toast.LENGTH_SHORT).show()
                    return@post
                }

                pendingArchiveUris = urisToDelete
                pendingCreatedArchiveFiles = createdFiles

                // Trigger Scoped Storage delete request via MediaStore
                try {
                    val pendingIntent = MediaStore.createDeleteRequest(contentResolver, urisToDelete)
                    val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    deleteRequestLauncher.launch(request)
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Silme isteği oluşturulamadı: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    // Rollback created files
                    backgroundExecutor.execute {
                        for (file in createdFiles) {
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * Architectural Requirement:
     * Restore selected archive files to DCIM/Restored via MediaStore API
     * and delete private copy with file.delete().
     */
    private fun restoreSelectedPhotos(selected: List<MediaItem>) {
        showLoading("Fotoğraflar galeriye geri yükleniyor...")

        backgroundExecutor.execute {
            var restoredCount = 0

            for (item in selected) {
                val file = item.archiveFile ?: continue
                if (!file.exists()) continue

                val extension = file.extension.lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
                val displayName = item.displayName.ifBlank { "Restored_${System.currentTimeMillis()}.jpg" }

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Restored")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val insertedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (insertedUri != null) {
                    var success = false
                    try {
                        contentResolver.openOutputStream(insertedUri)?.use { outputStream ->
                            FileInputStream(file).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(insertedUri, values, null, null)
                        file.delete()
                        success = true
                    } catch (e: Exception) {
                        contentResolver.delete(insertedUri, null, null)
                    }

                    if (success) {
                        restoredCount++
                    }
                }
            }

            mainHandler.post {
                hideLoading()
                adapter.clearSelection()
                ThumbnailLoader.clearCache()
                Toast.makeText(
                    this,
                    "$restoredCount fotoğraf galeriye (DCIM/Restored) aktarıldı.",
                    Toast.LENGTH_SHORT
                ).show()
                loadMedia()
            }
        }
    }

    private fun showFullscreenViewer(item: MediaItem) {
        val dialog = FullscreenViewerDialog(
            activity = this,
            contentResolver = contentResolver,
            item = item,
            onActionClick = { targetItem ->
                if (targetItem.isArchive) {
                    restoreSelectedPhotos(listOf(targetItem))
                } else {
                    archiveSelectedPhotos(listOf(targetItem))
                }
            }
        )
        dialog.show()
    }

    private class GridSpacingItemDecoration(
        private val spanCount: Int,
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val column = position % spanCount

            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing
            }
        }
    }
}
