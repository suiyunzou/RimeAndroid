// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.view.inputmethod.InputConnection
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Proxy

class ActionKeyDispatcherTest : StringSpec({
    fun editor(action: (Int) -> Boolean): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java),
    ) { _, method, arguments ->
        if (method.name == "performContextMenuAction") action(arguments!![0] as Int)
        else throw IllegalStateException("unexpected editor call ${method.name}")
    } as InputConnection

    "dispatches only Android standard context actions" {
        val calls = mutableListOf<Int>()
        val connection = editor { calls.add(it); true }
        listOf("action:cut", "action:copy", "action:paste", "action:select_all").forEach {
            ActionKeyDispatcher.execute(connection, it) shouldBe true
        }
        calls shouldBe listOf(android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.selectAll)
    }
    "does not commit text or interpret arbitrary commands" {
        var calls = 0
        val connection = editor { calls++; true }
        listOf("text:hello", "run", "", "action:unknown").forEach {
            ActionKeyDispatcher.execute(connection, it) shouldBe false
        }
        calls shouldBe 0
    }
    "null rejected and disconnected editors report failure" {
        ActionKeyDispatcher.execute(null, "paste") shouldBe false
        ActionKeyDispatcher.execute(editor { false }, "paste") shouldBe false
        ActionKeyDispatcher.execute(editor { error("disconnected") }, "paste") shouldBe false
    }
    "copy entire document selects before copying" {
        val calls=mutableListOf<Int>()
        ActionKeyDispatcher.execute(editor { calls.add(it);true },"action:copy_all") shouldBe true
        calls shouldBe listOf(android.R.id.selectAll,android.R.id.copy)
    }
    "clear does nothing when full selection is rejected" {
        val calls=mutableListOf<Int>()
        ActionKeyDispatcher.execute(editor { calls.add(it);false },"action:clear_all") shouldBe false
        calls shouldBe listOf(android.R.id.selectAll)
    }
    "clear replaces full selection without overwriting the clipboard" {
        val calls=mutableListOf<String>()
        val connection=Proxy.newProxyInstance(InputConnection::class.java.classLoader,arrayOf(InputConnection::class.java)) { _,method,args ->
            when(method.name){
                "performContextMenuAction" -> { args!![0] shouldBe android.R.id.selectAll;calls.add("select");true }
                "commitText" -> { args!![0] shouldBe "";args[1] shouldBe 1;calls.add("clear");true }
                else -> error("Unexpected editor operation ${method.name}")
            }
        } as InputConnection
        ActionKeyDispatcher.execute(connection,"action:clear_all") shouldBe true
        calls shouldBe listOf("select","clear")
    }
})
