package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseSavingTest {

    private fun boolSession(numBools: Int): PropagationSession =
        PropagationSession(Problem(numBools, 0, emptyArray(), emptyList()))

    @Test
    fun `disabled phase saving leaves the value order unchanged`() {
        val phase = PhaseSaving(1, 0, BacktrackParams())
        assertEquals(listOf(0L, 1L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `a captured bool phase is tried first`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(phaseSaving = true))
        phase.capture(VarRef.Bool(0), session)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `a captured int phase is tried first`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyList()))
        session.pinInt(0, 3)
        val phase = PhaseSaving(0, 1, BacktrackParams(phaseSaving = true))
        phase.capture(VarRef.IntVar(0), session)
        assertEquals(listOf(3L, 1L, 5L), phase.applyPhase(VarRef.IntVar(0), sequenceOf(1, 3, 5)).toList())
    }

    @Test
    fun `target phase is preferred while in TARGET mode`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true))
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `captureTargetIfDeeper only snapshots at a new maximum depth`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true))
        phase.captureTargetIfDeeper(session, trailSize = 3)
        // A shallower prefix must not overwrite the target: flip the assignment, re-offer at depth 1.
        session.pinBool(0, false)
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `onConflictTick rotates the polarity source off TARGET after the rephase interval`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true, rephaseInterval = 2))
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
        // Two conflicts rotate TARGET -> SAVED; with no saved bool phase, SAVED mode falls through to the
        // heuristic order unchanged.
        phase.onConflictTick()
        phase.onConflictTick()
        assertEquals(listOf(0L, 1L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }
}
