/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar

import com.osfans.trime.ime.bar.QuickBarStateMachine.BooleanKey.BarBoardWindowActive
import com.osfans.trime.ime.bar.QuickBarStateMachine.BooleanKey.CandidateEmpty
import com.osfans.trime.ime.bar.QuickBarStateMachine.BooleanKey.CandidatesOnBoard
import com.osfans.trime.ime.bar.QuickBarStateMachine.State.Always
import com.osfans.trime.ime.bar.QuickBarStateMachine.State.Candidate
import com.osfans.trime.ime.bar.QuickBarStateMachine.State.Tab
import com.osfans.trime.ime.bar.QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached
import com.osfans.trime.ime.bar.QuickBarStateMachine.TransitionEvent.CandidatesUpdated
import com.osfans.trime.ime.bar.QuickBarStateMachine.TransitionEvent.WindowDetached
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class QuickBarStateMachineTest :
    StringSpec({
        "ordinary keyboard shows candidates and restores its toolbar" {
            val machine = QuickBarStateMachine.new { }
            machine.currentState shouldBe Always
            machine.push(CandidatesUpdated, CandidateEmpty to false)
            machine.currentState shouldBe Candidate
            machine.push(CandidatesUpdated, CandidateEmpty to true)
            machine.currentState shouldBe Always
        }

        "symbol panel restores its title after each candidate selection" {
            val states = mutableListOf<QuickBarStateMachine.State>()
            val machine = QuickBarStateMachine.new { states.add(it) }
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to true)
            machine.currentState shouldBe Tab
            repeat(3) {
                machine.push(CandidatesUpdated, CandidateEmpty to false)
                machine.currentState shouldBe Candidate
                machine.push(CandidatesUpdated, CandidateEmpty to true)
                machine.currentState shouldBe Tab
            }
            states shouldBe listOf(Tab, Candidate, Tab, Candidate, Tab, Candidate, Tab)
        }

        "symbol panel can open with candidates already present" {
            val machine = QuickBarStateMachine.new { }
            machine.push(CandidatesUpdated, CandidateEmpty to false)
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to true)
            machine.currentState shouldBe Candidate
            machine.push(CandidatesUpdated, CandidateEmpty to true)
            machine.currentState shouldBe Tab
        }

        "leaving a symbol panel with candidates clears panel ownership" {
            val machine = QuickBarStateMachine.new { }
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to true)
            machine.push(CandidatesUpdated, CandidateEmpty to false)
            machine.push(WindowDetached, BarBoardWindowActive to false, CandidatesOnBoard to false)
            machine.currentState shouldBe Candidate
            machine.push(CandidatesUpdated, CandidateEmpty to true)
            machine.currentState shouldBe Always
        }

        "switching to a title-first panel suppresses existing and updated candidates" {
            val machine = QuickBarStateMachine.new { }
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to true)
            machine.push(CandidatesUpdated, CandidateEmpty to false)
            machine.push(WindowDetached, BarBoardWindowActive to false, CandidatesOnBoard to false)
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to false)
            machine.currentState shouldBe Tab
            machine.push(CandidatesUpdated, CandidateEmpty to true)
            machine.currentState shouldBe Tab
            machine.push(CandidatesUpdated, CandidateEmpty to false)
            machine.currentState shouldBe Tab
            machine.push(WindowDetached, BarBoardWindowActive to false, CandidatesOnBoard to false)
            machine.currentState shouldBe Candidate
        }

        "empty title-first panel returns to the ordinary toolbar" {
            val machine = QuickBarStateMachine.new { }
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to false)
            machine.currentState shouldBe Tab
            machine.push(CandidatesUpdated, CandidateEmpty to true)
            machine.currentState shouldBe Tab
            machine.push(WindowDetached, BarBoardWindowActive to false, CandidatesOnBoard to false)
            machine.currentState shouldBe Always
        }

        "repeated panel switches do not retain a previous panel policy" {
            val machine = QuickBarStateMachine.new { }
            repeat(3) {
                machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to false)
                machine.push(CandidatesUpdated, CandidateEmpty to false)
                machine.currentState shouldBe Tab
                machine.push(WindowDetached, BarBoardWindowActive to false, CandidatesOnBoard to false)
                machine.currentState shouldBe Candidate
                machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to true)
                machine.currentState shouldBe Candidate
                machine.push(CandidatesUpdated, CandidateEmpty to true)
                machine.currentState shouldBe Tab
                machine.push(WindowDetached, BarBoardWindowActive to false, CandidatesOnBoard to false)
                machine.currentState shouldBe Always
            }
        }

        "duplicate candidate updates do not emit redundant visibility changes" {
            val states = mutableListOf<QuickBarStateMachine.State>()
            val machine = QuickBarStateMachine.new { states.add(it) }
            machine.push(BarBoardWindowAttached, BarBoardWindowActive to true, CandidatesOnBoard to true)
            repeat(3) { machine.push(CandidatesUpdated, CandidateEmpty to false) }
            repeat(3) { machine.push(CandidatesUpdated, CandidateEmpty to true) }
            states shouldBe listOf(Tab, Candidate, Tab)
        }
    })
