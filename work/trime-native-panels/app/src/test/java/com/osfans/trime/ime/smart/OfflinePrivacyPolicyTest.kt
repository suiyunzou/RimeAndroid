package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OfflinePrivacyPolicyTest : FunSpec({
    test("password types including visible web and numeric are sensitive") {
        for (type in listOf(0x81, 0x91, 0xe1, 0x12)) OfflinePrivacyPolicy.isSensitive(type, 0) shouldBe true
    }
    test("no personalized learning covers otherwise ordinary text") {
        OfflinePrivacyPolicy.isSensitive(1, 0x01000006) shouldBe true
    }
    test("normal text number email and unrelated options remain ordinary") {
        for (type in listOf(1, 2, 0x21, 0x11)) OfflinePrivacyPolicy.isSensitive(type, 6) shouldBe false
    }
    test("ordinary editor restores policy after sensitive editor") {
        OfflinePrivacyPolicy.updateEditor(0x81, 0)
        OfflinePrivacyPolicy.sensitiveEditor shouldBe true
        OfflinePrivacyPolicy.updateEditor(1, 0)
        OfflinePrivacyPolicy.sensitiveEditor shouldBe false
    }
})
