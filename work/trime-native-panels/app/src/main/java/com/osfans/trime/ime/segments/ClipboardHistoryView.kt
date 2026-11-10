package com.osfans.trime.ime.segments

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.theme.ThemeScope
import splitties.dimensions.dp

/** Paste from the text area; open word cards from the trailing arrow. */
internal class ClipboardHistoryView(
    context: Context,
    private val scope: ThemeScope,
    private val paste: (String) -> Unit,
    private val openSegments: (String) -> Unit,
) : RecyclerView(context) {
    class Row(root: LinearLayout, val label: TextView, val arrow: TextView, val line: View) : ViewHolder(root)

    val clips = object : PagingDataAdapter<DatabaseBean, Row>(object : DiffUtil.ItemCallback<DatabaseBean>() {
        override fun areItemsTheSame(a: DatabaseBean, b: DatabaseBean) = a.id == b.id
        override fun areContentsTheSame(a: DatabaseBean, b: DatabaseBean) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val label = TextView(context).apply {
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(context.dp(18), 0, 0, 0)
                isFocusable = true
            }
            val arrow = TextView(context).apply {
                text = "›"
                textSize = 28f
                gravity = Gravity.CENTER
                isFocusable = true
            }
            val line = View(context)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    addView(label, LinearLayout.LayoutParams(0, -1, 1f))
                    addView(arrow, LinearLayout.LayoutParams(context.dp(48), -1))
                }, LinearLayout.LayoutParams(-1, context.dp(64)))
                addView(line, LinearLayout.LayoutParams(-1, context.dp(1)).apply {
                    marginStart = context.dp(18)
                    marginEnd = context.dp(18)
                })
            }
            return Row(root, label, arrow, line)
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val clip = getItem(position) ?: return
            val value = clip.text.orEmpty()
            holder.label.text = value.take(512).replace('\n', ' ')
            holder.label.setTextColor(scope.colors.keyTextColor)
            holder.arrow.setTextColor(ColorUtils.setAlphaComponent(scope.colors.keyTextColor, 100))
            holder.line.setBackgroundColor(ColorUtils.setAlphaComponent(scope.colors.keyTextColor, 24))
            holder.label.contentDescription = "粘贴记录 ${value.take(128)}"
            holder.arrow.contentDescription = "分词记录 ${value.take(128)}"
            holder.label.setOnClickListener { paste(value) }
            holder.arrow.setOnClickListener { openSegments(value) }
        }
    }

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = clips
        itemAnimator = null
        contentDescription = "最近记录列表"
    }
}
