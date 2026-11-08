// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.sqrt

/** Bounded arithmetic only. Percent means divide the preceding operand by 100. */
object CalculatorEngine {
    const val MAX_LENGTH = 256
    const val MAX_DEPTH = 32
    private val precision = MathContext(16, RoundingMode.HALF_UP)

    fun evaluate(expression: String): String {
        require(expression.isNotBlank()) { "请输入算式" }
        require(expression.length <= MAX_LENGTH) { "算式过长" }
        return try {
            val parser = Parser(expression)
            val result = parser.expression(0)
            parser.finish()
            val normalized = if (result.signum() == 0) BigDecimal.ZERO else result.stripTrailingZeros()
            val text = normalized.toPlainString()
            require(text.length <= 512) { "结果过长" }
            text
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("无法计算此算式", error)
        }
    }

    private class Parser(private val text: String) {
        private var position = 0
        private fun skip() { while (position < text.length && text[position].isWhitespace()) position++ }
        private fun take(vararg chars: Char): Boolean {
            skip()
            if (position < text.length && text[position] in chars) { position++; return true }
            return false
        }
        private fun checkDepth(depth: Int) { require(depth <= MAX_DEPTH) { "括号或前缀过多" } }
        fun finish() { skip(); require(position == text.length) { "算式含有不支持的字符或缺少运算符" } }
        fun expression(depth: Int): BigDecimal {
            checkDepth(depth)
            var value = product(depth)
            while (true) {
                value = when {
                    take('+') -> value.add(product(depth), precision)
                    take('-', '−') -> value.subtract(product(depth), precision)
                    else -> return value
                }
            }
        }
        private fun product(depth: Int): BigDecimal {
            var value = unary(depth)
            while (true) {
                value = when {
                    take('*', '×') -> value.multiply(unary(depth), precision)
                    take('/', '÷') -> {
                        val divisor = unary(depth)
                        require(divisor.signum() != 0) { "不能除以零" }
                        value.divide(divisor, precision)
                    }
                    else -> return value
                }
            }
        }
        private fun unary(depth: Int): BigDecimal {
            checkDepth(depth)
            if (take('+')) return unary(depth + 1)
            if (take('-', '−')) return unary(depth + 1).negate()
            if (take('√')) {
                val value = unary(depth + 1)
                require(value.signum() >= 0) { "负数不能开平方" }
                return BigDecimal.valueOf(sqrt(value.toDouble())).round(precision)
            }
            var value = if (take('(', '（')) {
                val result = expression(depth + 1)
                require(take(')', '）')) { "缺少右括号" }
                result
            } else number()
            while (take('%', '％')) value = value.divide(BigDecimal(100), precision)
            return value
        }
        private fun number(): BigDecimal {
            skip()
            val start = position
            var dots = 0
            var digits = 0
            while (position < text.length) {
                val character = text[position]
                if (character in '0'..'9') digits++ else if (character == '.') dots++ else break
                position++
            }
            require(digits > 0 && dots <= 1) { "请输入有效数字" }
            return text.substring(start, position).toBigDecimal(precision)
        }
    }
}
