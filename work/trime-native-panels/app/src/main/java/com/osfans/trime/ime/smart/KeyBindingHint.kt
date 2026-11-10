package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import kotlin.math.roundToInt

/** Draws default and custom flick labels in the same keycap corner. */
internal class KeyBindingHint(private val context: Context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val inkBounds = Rect()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private val icons = mutableMapOf<String, IconicsDrawable>()

    fun draw(canvas: Canvas, binding: String?, fallback: String, area: RectF, size: Float, color: Int,
        typeface: Typeface? = null, ascii: Boolean? = null) {
        if (area.isEmpty || size <= 0f) return
        // Reserve one square cell per single symbol. The cell never enters the letter area.
        val density = context.resources.displayMetrics.density
        val side = minOf(size.coerceIn(9.5f * density, 10f * density), area.width(), area.height())
        if (side < 3f) return
        val centerX = area.right - side / 2f
        val centerY = area.top + side / 2f
        val glyph = when (binding) {
            "action:cut" -> CommunityMaterial.Icon.cmd_content_cut
            "action:paste" -> CommunityMaterial.Icon.cmd_content_paste
            "action:copy_all" -> CommunityMaterial.Icon2.cmd_file_document_multiple_outline
            "action:clear_all" -> CommunityMaterial.Icon.cmd_delete_outline
            "action:copy" -> CommunityMaterial.Icon.cmd_content_copy
            "action:select_all" -> CommunityMaterial.Icon3.cmd_select_all
            else -> null
        }
        if (glyph != null) {
            val iconSize = side.toInt()
            val icon = icons.getOrPut(requireNotNull(binding)) { IconicsDrawable(context, glyph) }
            val right = area.right.toInt()
            val top = area.top.roundToInt()
            icon.setTint(color)
            icon.setBounds(right - iconSize, top, right, top + iconSize)
            icon.draw(canvas)
            return
        }

        val raw = (if (binding == null) fallback else KeyBindingLabels.label(binding)).replace('\n', ' ').replace('\r', ' ')
        val text = ascii?.let { KeyboardPunctuation.localized(raw, it) } ?: raw
        if (text.isEmpty()) return
        if (drawMark(canvas, text, area.right - side, area.top, side, color)) return
        paint.color = color
        paint.typeface = Typeface.create(typeface, Typeface.NORMAL)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = side * 1.12f
        val hint = if (text.codePointCount(0, text.length) == 1) text
            else TextUtils.ellipsize(text, paint, area.width(), TextUtils.TruncateAt.END).toString()
        paint.getTextBounds(hint, 0, hint.length, inkBounds)
        if (inkBounds.isEmpty) return
        val inkWidth = inkBounds.width().toFloat()
        val inkHeight = inkBounds.height().toFloat()
        val single = hint.codePointCount(0, hint.length) == 1
        val scale = minOf(1f, (if (single) side else area.width()) / inkWidth, side / inkHeight)
        val x = if (single) centerX - inkWidth * scale / 2f else area.right - inkWidth * scale
        val checkpoint = canvas.save()
        canvas.clipRect(area)
        canvas.translate(x, centerY - inkHeight * scale / 2f)
        canvas.scale(scale, scale)
        canvas.drawText(hint, -inkBounds.left.toFloat(), -inkBounds.top.toFloat(), paint)
        canvas.restoreToCount(checkpoint)
    }

    /** Common marks use one pen width. Dots and dashes retain their recognizable shape. */
    private fun drawMark(canvas: Canvas, text: String, x: Float, y: Float, side: Float, color: Int): Boolean {
        val mark = if (text == "...") '…' else text.singleOrNull() ?: return false
        val shape = when (mark) {
            '！' -> '!'; '？' -> '?'; '：' -> ':'; '；' -> ';'; '，' -> ','
            '（' -> '('; '）' -> ')'; '－', '−' -> '-'; '＋' -> '+'; '＿' -> '_'
            else -> mark
        }
        if (shape !in "!?:;.,、。…-+_/()\"'‘’“”") return false
        val checkpoint = canvas.save()
        canvas.translate(x, y)
        canvas.scale(side / 12f, side / 12f)
        line.color = color
        paint.color = color
        fun dot(cx: Float, cy: Float) = canvas.drawCircle(cx, cy, .95f, paint)
        fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, line)
        when (shape) {
            '!' -> { stroke(6f, 1.4f, 6f, 7.1f); dot(6f, 10.6f) }
            ':' -> { dot(6f, 1.4f); dot(6f, 10.6f) }
            ';' -> { dot(6f, 1.4f); stroke(6.5f, 7.4f, 5.4f, 10.6f) }
            '?' -> {
                path.rewind()
                path.moveTo(3.1f, 3.5f)
                path.cubicTo(3.1f, .2f, 9.5f, .5f, 9.1f, 3.8f)
                path.cubicTo(8.9f, 5.7f, 6f, 5.6f, 6f, 7.2f)
                canvas.drawPath(path, line)
                dot(6f, 10.6f)
            }
            '.' -> dot(6f, 6f)
            ',' -> stroke(6.8f, 4f, 5.2f, 8f)
            '、' -> stroke(4.6f, 4f, 7.4f, 8f)
            '。' -> canvas.drawCircle(6f, 6f, 2f, line)
            '…' -> { dot(2f, 6f); dot(6f, 6f); dot(10f, 6f) }
            '-', '+', '_' -> {
                val row = if (shape == '_') 8.8f else 6f
                stroke(1.4f, row, 10.6f, row)
                if (shape == '+') stroke(6f, 1.4f, 6f, 10.6f)
            }
            '/' -> stroke(8.5f, 1.4f, 3.5f, 10.6f)
            '\'', '"', '‘', '’', '“', '”' -> {
                val double = shape == '"' || shape == '“' || shape == '”'
                val open = shape == '‘' || shape == '“'
                val start = if (double) 3.8f else 6f
                val end = if (double) 8.2f else start
                fun quote(cx: Float) {
                    if (shape == '\'' || shape == '"') {
                        stroke(cx + .7f, 3.7f, cx - .7f, 8.3f)
                    } else {
                        val tail = if (open) -1.3f else 1.3f
                        val head = if (open) 8.4f else 3.6f
                        path.rewind()
                        path.moveTo(cx + tail, 12f - head)
                        path.quadTo(cx - tail, 6f, cx, head)
                        canvas.drawPath(path, line)
                        dot(cx, head)
                    }
                }
                quote(start)
                if (double) quote(end)
            }
            '(', ')' -> {
                val edge = if (shape == '(') 7.8f else 4.2f
                val bend = if (shape == '(') 4f else 8f
                path.rewind()
                path.moveTo(edge, 1.4f)
                path.quadTo(bend, 6f, edge, 10.6f)
                canvas.drawPath(path, line)
            }
        }
        canvas.restoreToCount(checkpoint)
        return true
    }
}
