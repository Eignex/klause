package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomsTest {
    private fun freshState(numVars: Int, hi: Int, numBoolVars: Int = 0): PropagationState {
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = numVars,
            intDomains = Array(numVars) { IntDomain(0, hi.toLong()) },
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    private fun PropagationState.beginLevel(v: Int, fid: Int) {
        levelToDecisionVar.add(problem.numBoolVars + v)
        currentLevel = levelToDecisionVar.size
        currentFactor = fid
    }

    @Test
    fun `a bound literal at the live endpoint should cite the move that established it`() {
        val s = freshState(numVars = 2, hi = 9, numBoolVars = 1)
        val premise = intArrayOf(Lit.make(0, false))

        s.beginLevel(0, fid = 0)
        s.tightenIntMin(0, 2, premise)
        val atomId = s.atomVarGe(0, 2) - s.problem.numBoolVars

        assertEquals(premise.toList(), s.atomAntecedentsDerived(atomId)?.toList())
    }

    @Test
    fun `an overshot bound literal should cite the move that first reached it`() {
        val s = freshState(numVars = 2, hi = 9, numBoolVars = 1)
        val premise = intArrayOf(Lit.make(0, false))

        s.beginLevel(0, fid = 0)
        s.tightenIntMin(0, 2, premise)
        val atomId = s.atomVarGe(0, 2) - s.problem.numBoolVars

        s.beginLevel(1, fid = 1)
        s.tightenIntMin(0, 5, intArrayOf(Lit.make(s.atomVarLe(1, 4), false)))

        assertEquals(true, s.atomCurrentTruth(atomId))
        assertEquals(premise.toList(), s.atomAntecedentsDerived(atomId)?.toList())
    }

    @Test
    fun `a late equality exclusion should keep the level of the first bound crossing`() {
        for (lower in listOf(false, true)) {
            val s = freshState(numVars = 2, hi = 9, numBoolVars = 1)
            s.beginLevel(0, fid = 0)
            if (lower) s.tightenIntMin(0, 6, null) else s.tightenIntMax(0, 4, null)
            val atomId = s.atomVarEq(0, 5) - s.problem.numBoolVars
            s.beginLevel(1, fid = 1)
            if (lower) s.tightenIntMin(0, 8, null) else s.tightenIntMax(0, 2, null)

            assertEquals(false, s.atomCurrentTruth(atomId))
            assertEquals(1, s.atomLevelForConflict(atomId))
        }
    }

    @Test
    fun `a carved value should keep its carve level when a later move wakes its eq atom`() {
        val s = freshState(numVars = 1, hi = 5)
        check(s.excludeIntValue(0, 3, null))
        val atom = s.atomVarEq(0, 3)
        s.beginLevel(0, fid = -1)

        check(s.tightenIntMax(0, 2, null))

        assertEquals(0, s.atomLevelForConflict(atom))
    }

    @Test
    fun `an untrailed root hole should keep its history when a later bound precedes its eq atom`() {
        for (prepared in listOf(false, true)) {
            val direct = freshState(numVars = 1, hi = 5)
            val s = if (prepared) {
                PropagationState.preparation(direct.projection, pbLearning = false)
                    .asSequence().filterNotNull().last()
            } else {
                direct
            }
            s.undoLogging = false
            assertTrue(s.excludeIntValue(0, 3, null))
            s.undoLogging = true
            s.beginLevel(0, fid = -1)
            assertTrue(s.tightenIntMax(0, 2, null))

            val atom = s.atomVarEq(0, 3)

            assertEquals(false, s.atomCurrentTruth(atom))
            assertEquals(0, s.atomLevelForConflict(atom))
            assertNull(s.atomAntecedentsDerived(atom))
            assertEquals(0, s.carvedAt(0, 3))
            assertFalse(s.inDomainAt(0, 3, 0))
        }
    }

    @Test
    fun `a batch bound should cite its premise and only earlier crossed holes`() {
        val s = freshState(numVars = 1, hi = 9, numBoolVars = 2)
        assertTrue(s.pinBoolAsDecision(0, true))
        val earlierReason = intArrayOf(Lit.make(0, false))
        assertTrue(s.excludeIntValue(0, 1, earlierReason))
        assertTrue(s.excludeIntValue(0, 8, earlierReason))
        assertTrue(s.pinBoolAsDecision(1, true))
        val batchReason = intArrayOf(Lit.make(1, false))

        assertTrue(s.excludeIntValues(0, longArrayOf(0, 4, 9), batchReason))

        for ((atom, hole) in listOf(s.atomVarGe(0, 2) to 1L, s.atomVarLe(0, 7) to 8L)) {
            assertEquals(
                listOf(Lit.make(1, false), Lit.make(s.atomVarEq(0, hole), true)),
                s.atomAntecedentsDerived(atom - s.problem.numBoolVars)?.toList(),
            )
        }
    }

    @Test
    fun `adjacent unconditional root holes should retain an earlier conditional carve reason`() {
        val s = freshState(numVars = 2, hi = 9)
        s.undoLogging = false
        assertTrue(s.tightenIntMax(1, 1, null))
        val reason = intArrayOf(Lit.make(s.atomVarLe(1, 1), false))
        assertTrue(s.excludeIntValue(0, 3, reason))
        assertTrue(s.excludeIntValues(0, longArrayOf(2, 4, 5), null))
        s.undoLogging = true
        s.beginLevel(0, fid = -1)
        assertTrue(s.tightenIntMax(0, 1, null))

        val atom = s.atomVarEq(0, 3)

        assertEquals(reason.toList(), s.atomAntecedentsDerived(atom)?.toList())
        assertEquals(0, s.atomLevelForConflict(atom))
        assertEquals(0, s.carvedAt(0, 3))
        assertNull(s.holeReasonFor(0, 4))
    }

    @Test
    fun `untrailed root holes should survive sibling scopes while conditional holes retract`() {
        val s = freshState(numVars = 2, hi = 9)
        s.undoLogging = false
        assertTrue(s.excludeIntValues(0, longArrayOf(2, 3, 4), null))
        s.undoLogging = true
        val root = s.mark()
        s.beginLevel(1, fid = -1)
        assertTrue(s.tightenIntMax(1, 1))
        val reason = intArrayOf(Lit.make(s.atomVarLe(1, 1), false))
        assertTrue(s.excludeIntValue(0, 6, reason))
        assertEquals(reason.toList(), s.holeReasonFor(0, 6)?.toList())

        s.undoTo(root)
        s.beginLevel(0, fid = -1)
        assertTrue(s.tightenIntMin(0, 5))
        val atom = s.atomVarEq(0, 3)

        assertEquals(0, s.atomLevelForConflict(atom))
        assertEquals(0, s.carvedAt(0, 3))
        assertNull(s.holeReasonFor(0, 3))
        assertFalse(s.holeHistHas(0, 6))
        assertTrue(6L in s.intDomains[0])
    }

    private fun assertReasonsCiteOnlyDeterminedAtoms(s: PropagationState, where: String) {
        for (id in 0 until s.atoms.intVar.size) {
            if (s.atomCurrentTruth(id) == null) continue
            val reason = s.atomAntecedentsDerived(id) ?: continue
            for (lit in reason) {
                val v = Lit.variable(lit)
                if (v < s.problem.numBoolVars) continue
                val citedId = v - s.problem.numBoolVars
                assertNotNull(
                    s.atomCurrentTruth(citedId),
                    "at $where: determined atom $id (var=${s.atoms.intVar[id]} ${s.atoms.kind[id]} " +
                        "${s.atoms.threshold[id]}) cites undetermined atom $citedId " +
                        "(var=${s.atoms.intVar[citedId]} ${s.atoms.kind[citedId]} ${s.atoms.threshold[citedId]})",
                )
            }
        }
    }

    @Test
    fun `a hole eq atom should keep its carve reason after a bound move widens back`() {
        val s = freshState(numVars = 2, hi = 6)
        val eqId = s.atomVarEq(0, 2) - s.problem.numBoolVars
        s.beginLevel(1, fid = -1)
        assertTrue(s.tightenIntMax(1, 1))
        s.beginLevel(0, fid = 0)
        val carveReason = intArrayOf(Lit.make(s.atomVarLe(1, 1), false))
        assertTrue(s.excludeIntValue(0, 2, carveReason))
        val carved = s.mark()
        s.beginLevel(0, fid = 1)
        assertTrue(s.tightenIntMin(0, 3))

        s.undoTo(carved)

        assertEquals(false, s.atomCurrentTruth(eqId))
        assertEquals(carveReason.toList(), assertNotNull(s.atomAntecedentsDerived(eqId)).toList())
        assertEquals(2, s.atomLevelForConflict(eqId))
        assertReasonsCiteOnlyDeterminedAtoms(s, "after backtrack")
    }

    private fun materializeAllAtoms(s: PropagationState, numVars: Int, hi: Int) {
        for (v in 0 until numVars) {
            for (k in 0..hi) {
                s.atomVarGe(v, k.toLong())
                s.atomVarLe(v, k.toLong())
                s.atomVarEq(v, k.toLong())
            }
        }
    }

    // Stored levels are advisory and clamped on read, so a level above the live decision count is valid.
    private fun assertAtomTruthConsistent(s: PropagationState, where: String) {
        for (id in 0 until s.atoms.intVar.size) {
            val v = s.atoms.intVar[id]
            assertEquals(
                s.atomTruthOf(v, s.atoms.kind[id], s.atoms.threshold[id]),
                s.atomCurrentTruth(id),
                "stored atom truth diverged from the domain at $where: atom id=$id " +
                    "(var=$v kind=${s.atoms.kind[id]} k=${s.atoms.threshold[id]}) domain=${s.intDomains[v]}",
            )
        }
    }

    private fun randomDecision(s: PropagationState, v: Int, rng: Random): Boolean {
        val d = s.intDomains[v]
        if (d.min == d.max) return false
        s.levelToDecisionVar.add(s.problem.numBoolVars + v)
        s.currentLevel = s.levelToDecisionVar.size
        s.currentFactor = -1
        return when (rng.nextInt(3)) {
            0 -> s.tightenIntMin(v, rng.nextInt((d.min + 1).toInt(), (d.max + 1).toInt()).toLong())

            1 -> s.tightenIntMax(v, rng.nextInt(d.min.toInt(), d.max.toInt()).toLong())

            else -> {
                val value = rng.nextInt(d.min.toInt(), (d.max + 1).toInt()).toLong()
                s.excludeIntValue(v, value)
            }
        }
    }

    @Test
    fun `stored atom truth should match the live domain across push and pop`() {
        val numVars = 3
        val hi = 6
        val rng = Random(0x5eed)
        repeat(12) { trial ->
            val s = freshState(numVars, hi)
            materializeAllAtoms(s, numVars, hi)
            assertAtomTruthConsistent(s, "root/trial=$trial")
            val marks = ArrayDeque<PropagationState.LevelMark>()
            marks.addLast(s.mark())
            repeat(40) { step ->
                val pop = marks.size > 1 && rng.nextInt(3) == 0
                if (pop) {
                    val target = rng.nextInt(0, marks.size - 1)
                    while (marks.size > target + 1) marks.removeLast()
                    s.undoTo(marks.last())
                } else {
                    val v = rng.nextInt(numVars)
                    if (randomDecision(s, v, rng)) marks.addLast(s.mark())
                }
                assertAtomTruthConsistent(s, "trial=$trial step=$step pop=$pop")
            }
        }
    }

    @Test
    fun `a derived bound atom should explain through only the opposite bound that drives it`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 8),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.pinBoolAsDecision(0, true)
        state.runToFixpoint(allFactors = false)

        val atomId = state.atomIdOf(state.atomVarGe(1, 3))

        assertEquals(true, state.atomCurrentTruth(atomId))
        assertEquals(1, state.atomLevelForConflict(atomId))
        val antecedents = requireNotNull(state.atomAntecedentsDerived(atomId)).toSet()
        assertTrue(Lit.make(state.atomVarLe(0, 5), false) in antecedents)
        assertFalse(Lit.make(state.atomVarGe(0, 5), false) in antecedents)
    }

    @Test
    fun `a learned clause over atom literals should unit propagate the implied int bound`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = emptyArray(),
        )
        val state = PropagationState(problem, Assumptions.None)
        val clause = Clause(intArrayOf(Lit.make(state.atomVarGe(0, 5), true), Lit.make(state.atomVarGe(1, 7), true)))
        state.addLearnedClause(clause, lbd = 2)
        state.currentLevel = 1
        check(state.tightenIntMax(0, 4))

        val conflict = state.runToFixpoint(allFactors = false)

        assertNull(conflict)
        assertEquals(7, state.intDomains[1].min)
    }
}
