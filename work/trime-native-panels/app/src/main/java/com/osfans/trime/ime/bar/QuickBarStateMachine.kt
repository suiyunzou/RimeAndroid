/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import com.osfans.trime.ime.bar.QuickBarStateMachine.BooleanKey.BarBoardWindowActive
import com.osfans.trime.ime.bar.QuickBarStateMachine.BooleanKey.CandidateEmpty
import com.osfans.trime.ime.bar.QuickBarStateMachine.BooleanKey.CandidatesOnBoard
import com.osfans.trime.ime.bar.QuickBarStateMachine.State.Always
import com.osfans.trime.ime.bar.QuickBarStateMachine.State.Candidate
import com.osfans.trime.ime.bar.QuickBarStateMachine.State.Tab
import com.osfans.trime.util.EventStateMachine

object QuickBarStateMachine {
    enum class State {
        Always,
        Candidate,
        Tab,
    }

    enum class BooleanKey : EventStateMachine.BooleanStateKey {
        CandidateEmpty,
        BarBoardWindowActive,
        CandidatesOnBoard,
    }

    enum class TransitionEvent : EventStateMachine.TransitionEvent<State, BooleanKey> {
        CandidatesUpdated,
        BarBoardWindowAttached,
        WindowDetached,
        ;

        override fun accept(
            initialState: State,
            currentState: State,
            useBoolean: (BooleanKey) -> Boolean?,
        ): State {
            val boardActive = useBoolean(BarBoardWindowActive) == true
            val hasCandidates = useBoolean(CandidateEmpty) == false
            // Candidate visibility does not detach the panel. Keep its ownership
            // so its title returns when the candidate list becomes empty.
            return when {
                boardActive && useBoolean(CandidatesOnBoard) != true -> Tab
                hasCandidates -> Candidate
                boardActive -> Tab
                else -> Always
            }
        }
    }

    fun new(block: (State) -> Unit) = EventStateMachine<State, TransitionEvent, BooleanKey>(
        initialState = Always,
        externalBooleanStates =
        mutableMapOf(
            CandidateEmpty to true,
            BarBoardWindowActive to false,
            CandidatesOnBoard to false,
        ),
    ).apply {
        onNewStateListener = block
    }
}
