package com.osfans.trime.ime.smart

/** The reference keyboard permits exactly one flick direction at a time. */
enum class FlickDirection(val id: String) {
    OFF("off"), UP("up"), DOWN("down");

    fun allows(up: Boolean): Boolean = this == if (up) UP else DOWN

    companion object {
        fun fromId(value: String?): FlickDirection = entries.firstOrNull { it.id == value } ?: DOWN
    }
}
