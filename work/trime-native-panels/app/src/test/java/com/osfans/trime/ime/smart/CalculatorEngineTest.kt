// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CalculatorEngineTest : FunSpec({
    test("precedence and parentheses") {
        CalculatorEngine.evaluate("2+3×4") shouldBe "14"
        CalculatorEngine.evaluate("(2+3)*4") shouldBe "20"
        CalculatorEngine.evaluate("（2+3）÷2") shouldBe "2.5"
    }
    test("decimal arithmetic and normalized zero") {
        CalculatorEngine.evaluate("0.1+0.2") shouldBe "0.3"
        CalculatorEngine.evaluate("-0.0") shouldBe "0"
        CalculatorEngine.evaluate(".5 + 1.") shouldBe "1.5"
        CalculatorEngine.evaluate("1/3") shouldBe "0.3333333333333333"
    }
    test("unary signs roots and percentages") {
        CalculatorEngine.evaluate("-(-3)+ +2") shouldBe "5"
        CalculatorEngine.evaluate("√(9+7)") shouldBe "4"
        CalculatorEngine.evaluate("200×10%") shouldBe "20"
        CalculatorEngine.evaluate("50%%") shouldBe "0.005"
        CalculatorEngine.evaluate("-10%") shouldBe "-0.1"
    }
    test("division by zero and negative roots are rejected") {
        listOf("1/0", "1/(2-2)", "√(-1)").forEach { expression ->
            shouldThrow<IllegalArgumentException> { CalculatorEngine.evaluate(expression) }
        }
    }
    test("malformed expressions and executable text are rejected") {
        listOf("", " ", "()", "1+", "1..2", "2(3)", "1 2", "(1+2", "1+2)", "Runtime.exec(1)", "NaN", "1e3").forEach { expression ->
            shouldThrow<IllegalArgumentException> { CalculatorEngine.evaluate(expression) }
        }
    }
    test("input and recursion budgets are enforced") {
        shouldThrow<IllegalArgumentException> { CalculatorEngine.evaluate("1".repeat(257)) }
        shouldThrow<IllegalArgumentException> { CalculatorEngine.evaluate("(".repeat(34) + "1" + ")".repeat(34)) }
        shouldThrow<IllegalArgumentException> { CalculatorEngine.evaluate("-".repeat(34) + "1") }
    }
})
