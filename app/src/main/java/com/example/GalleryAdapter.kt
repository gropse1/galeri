package com.example

import android.content.ContentResolver
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GalleryAdapter(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: (MediaItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    val selectedKeys = mutableSetOf<String>()

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        // Clean up selected keys that no longer exist
        val existingKeys = newItems.map { it.uniqueKey }.toSet()
        selectedKeys.retainAll(existingKeys)
        notifyDataSetChanged()
        onSelectionChanged(selectedKeys.size)
    }

    fun isSelectionMode(): Boolean = selectedKeys.isNotEmpty()

    fun toggleSelection(item: MediaItem) {
        val key = item.uniqueKey
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key)
        } else {
            selectedKeys.add(key)
        }
        val index = items.indexOfFirst { it.uniqueKey == key }
        if (index != -1) {
            notifyItemChanged(index)
        }
        onSelectionChanged(selectedKeys.size)
    }

    fun selectAll() {
        selectedKeys.clear()
        items.forEach { selectedKeys.add(it.uniqueKey) }
        notifyDataSetChanged()
        onSelectionChanged(selectedKeys.size)
    }

    fun clearSelection() {
        selectedKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedItems(): List<MediaItem> {
        return items.filter { selectedKeys.contains(it.uniqueKey) }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val squareLayout = SquareFrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val imageView = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        squareLayout.addView(imageView)

        // Checkmark badge in top-right corner
        val badgeSize = (24 * parent.context.resources.displayMetrics.density).toInt()
        val badgeMargin = (6 * parent.context.resources.displayMetrics.density).toInt()

        val checkBadge = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(badgeMargin, badgeMargin, badgeMargin, badgeMargin)
            }
            gravity = Gravity.CENTER
            text = "✓"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1976D2"))
                setStroke(
                    (1.5f * parent.context.resources.displayMetrics.density).toInt(),
                    Color.WHITE
                )
            }
            background = bg
            visibility = View.GONE
        }
        squareLayout.addView(checkBadge)

        return ViewHolder(squareLayout, imageView, checkBadge)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isSelected = selectedKeys.contains(item.uniqueKey)

        // Architectural requirement: Selected items have alpha = 0.5f
        holder.itemView.alpha = if (isSelected) 0.5f else 1.0f
        holder.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE

        ThumbnailLoader.load(contentResolver, item, holder.imageView)

        holder.itemView.setOnClickListener {
            if (isSelectionMode()) {
                toggleSelection(item)
            } else {
                onItemClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
    }

    class ViewHolder(
        view: View,
        val imageView: ImageView,
        val checkBadge: View
    ) : RecyclerView.ViewHolder(view)

    private class SquareFrameLayout(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Force square aspect ratio (width == height)
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }
}
