// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class SmsCodeParserTest : FunSpec({
    test("explicit code languages and leading zeroes") {
        SmsCodeParser.extract("【测试】验证码：012345，5分钟有效") shouldBe "012345"
        SmsCodeParser.extract("驗證碼為 987654，請勿告知他人") shouldBe "987654"
        SmsCodeParser.extract("Your verification code is A2B9C1") shouldBe "A2B9C1"
        SmsCodeParser.extract("123456 is your security code.") shouldBe "123456"
    }
    test("numbers without code context and long phone numbers are rejected") {
        SmsCodeParser.extract("订单 123456 已发货") shouldBe null
        SmsCodeParser.extract("您的手机号码13800138000，验证码稍后发送") shouldBe null
        SmsCodeParser.extract("验证码已发送，金额1234元") shouldBe null
        SmsCodeParser.extract("验证码有效期到2026-11-09") shouldBe null
        SmsCodeParser.extract("验证码：ABCD") shouldBe null
        SmsCodeParser.extract("hotpot 1234") shouldBe null
        SmsCodeParser.extract("验证码已发送至尾号1234的手机") shouldBe null
        SmsCodeParser.extract("验证码发送失败，请拨打95588") shouldBe null
        SmsCodeParser.extract("验证码123456，如有问题拨打95588") shouldBe "123456"
    }
    test("nearest code wins over timestamp and trailing account") {
        SmsCodeParser.extract("2026年，验证码 765432，账户尾号 1234") shouldBe "765432"
    }
    test("bounded content and token sizes") {
        SmsCodeParser.extract("") shouldBe null
        SmsCodeParser.extract("验证码：123") shouldBe null
        SmsCodeParser.extract("验证码：123456789") shouldBe null
        SmsCodeParser.extract("验证码123456" + "甲".repeat(8192)) shouldBe null
    }
    test("code expires at exactly five minutes and cannot revive before receipt") {
        val code = SmsCodeEntry("123456", 1000L)
        code.isFresh(999L) shouldBe false
        code.isFresh(1000L) shouldBe true
        code.isFresh(300999L) shouldBe true
        code.isFresh(301000L) shouldBe false
    }
})
