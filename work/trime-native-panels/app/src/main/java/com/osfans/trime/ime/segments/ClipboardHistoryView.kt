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

/** Recent clips inside the same keyboard surface; selecting a row opens its word cards. */
internal class ClipboardHistoryView(
    context: Context,
    private val scope: ThemeScope,
    private val select: (String) -> Unit,
) : RecyclerView(context) {
    class Row(val root: LinearLayout, val label: TextView, val arrow: TextView, val line: View) : ViewHolder(root)

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
            }
            val arrow = TextView(context).apply { text = "›"; textSize = 28f; gravity = Gravity.CENTER }
            val line = View(context)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(18), 0, context.dp(18), 0)
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, context.dp(10), 0, context.dp(10))
                    addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(arrow, LinearLayout.LayoutParams(context.dp(28), -1))
                }, LinearLayout.LayoutParams(-1, context.dp(64)))
                addView(line, LinearLayout.LayoutParams(-1, context.dp(1)))
                isClickable = true
                isFocusable = true
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
            holder.root.contentDescription = "分词记录 ${value.take(128)}"
            holder.root.setOnClickListener { select(value) }
        }
    }

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = clips
        itemAnimator = null
        contentDescription = "最近记录列表"
    }
}
