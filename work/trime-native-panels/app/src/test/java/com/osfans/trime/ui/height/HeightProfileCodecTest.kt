package com.osfans.trime.ui.height

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException

class HeightProfileCodecTest : StringSpec({
    val fields = """
        p	style/keyboard_height	200
        l	style/keyboard_height_land	176
        p	preset_keyboards/settings_clean/keyboard_height	196
        l	preset_keyboards/compact_settings_clean/keyboard_height_land	164
    """.trimIndent()

    "read existing profile without orientation marker" {
        HeightProfileCodec.parse("# trime-height-dp: 208 184\nstyle: {}\n".toByteArray()).toList() shouldBe listOf(208, 184)
    }

    "portrait change preserves landscape and scales all matching boards" {
        val bytes = HeightProfileCodec.build(240, 184, false, fields.byteInputStream())
        HeightProfileCodec.parse(bytes).toList() shouldBe listOf(240, 184)
        val value = bytes.decodeToString()
        value shouldContain "# trime-height-orientation: p\n"
        value shouldContain "keyboard_height: 235\n"
        value shouldContain "keyboard_height_land: 171\n"
    }

    "landscape reset explicitly records direction and removes default overrides" {
        val value = HeightProfileCodec.build(200, 176, true, fields.byteInputStream()).decodeToString()
        value shouldBe "# trime-height-dp: 200 176\n# trime-height-orientation: l\nstyle: {}\nboards: {}\n"
    }

    "invalid values or fields fail before producing replacement data" {
        shouldThrow<IOException> { HeightProfileCodec.parse("# trime-height-dp: 143 176".toByteArray()) }
        shouldThrow<IOException> { HeightProfileCodec.parse("# trime-height-dp: 200 176 extra".toByteArray()) }
        shouldThrow<IOException> { HeightProfileCodec.build(200, 249, false, fields.byteInputStream()) }
        shouldThrow<IOException> { HeightProfileCodec.build(200, 176, false, "".byteInputStream()) }
        shouldThrow<IOException> {
            HeightProfileCodec.build(208, 176, false, "p\tstyle/keyboard_height_land\t200".byteInputStream())
        }
    }
})
