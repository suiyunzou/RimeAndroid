package com.osfans.trime.ime.smart

/** One vocabulary for the binding editor, preview, and live key corners. */
object KeyBindingLabels {
    val actions = linkedMapOf("cut" to "剪切", "paste" to "粘贴", "copy_all" to "复制全文", "clear_all" to "清空全文", "copy" to "复制选中", "select_all" to "全选")
    fun label(binding: String?): String = when {
        binding == null -> ""
        binding.startsWith("text:") -> binding.removePrefix("text:")
        binding.startsWith("action:") -> actions[binding.removePrefix("action:")].orEmpty()
        else -> ""
    }
    fun corner(binding: String?): String = when (binding) {
        "action:cut" -> "✂"
        "action:paste" -> "粘贴"
        "action:copy_all" -> "全文"
        "action:clear_all" -> "清空"
        "action:copy" -> "复制"
        "action:select_all" -> "全选"
        else -> label(binding).take(3)
    }
    fun keyName(key: String): String = when (key) {
        "space" -> "空格"; "shift" -> "Shift"; "backspace" -> "退格"
        "enter" -> "换行"; "emoji" -> "表情"; "qwerty" -> "ABC"
        else -> key.uppercase()
    }
    fun defaultHint(key: String, t9: Boolean = false): String {
        if (t9) return mapOf("@/." to "1", "abc" to "2", "def" to "3", "ghi" to "4", "jkl" to "5", "mno" to "6", "pqrs" to "7", "tuv" to "8", "wxyz" to "9")[key.lowercase()].orEmpty()
        val index = "qwertyuiop".indexOf(key).takeIf { key.length == 1 }
        if (index != null && index >= 0) return ((index + 1) % 10).toString()
        return mapOf("a" to "/", "s" to ":", "d" to ";", "f" to "(", "g" to ")", "h" to "$", "j" to "@", "k" to "&", "l" to "\"", "z" to "-", "x" to "'", "c" to "，", "v" to "?", "b" to "!", "n" to "。", "m" to "…", "shift" to "EN")[key].orEmpty()
    }
}
