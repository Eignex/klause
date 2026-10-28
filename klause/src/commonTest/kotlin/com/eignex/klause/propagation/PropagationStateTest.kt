package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropagationStateTest {
    private fun state(domains: Array<IntDomain>): PropagationState {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = emptyArray(),
        )
        return PropagationState(p, Assumptions.None)
    }

    @Test
    fun `excludeIntValue of an interior value should create a sparse domain`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3))
        val d = s.intDomains[0]
        assertEquals(1, d.min)
        assertEquals(5, d.max)
        assertEquals(4, d.values.size)
        assertFalse(3 in d)
        assertTrue(2 in d)
        assertTrue(4 in d)
    }

    @Test
    fun `excludeIntValue emptying a singleton domain should conflict`() {
        val s = state(arrayOf(IntDomain(5, 5)))
        assertFalse(s.excludeIntValue(0, 5))
    }

    @Test
    fun `tightenIntMin past holes should preserve the sparse representation`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3)) // domain = {1, 2, 4, 5}
        assertTrue(s.tightenIntMin(0, 2))
        val d = s.intDomains[0]
        assertEquals(2, d.min)
        assertEquals(5, d.max)
        assertEquals(3, d.values.size)
        assertFalse(3 in d)
        assertTrue(2 in d)
        assertTrue(4 in d)
    }

    @Test
    fun `tightenIntMax through holes should land past them`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3))
        assertTrue(s.tightenIntMax(0, 3)) // 3 itself is a hole; lands at 2.
        val d = s.intDomains[0]
        assertEquals(1, d.min)
        assertEquals(2, d.max)
        assertEquals(2, d.values.size)
    }

    private fun freshState(numVars: Int, hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = numVars,
            intDomains = Array(numVars) { IntDomain(0, hi.toLong()) },
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
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

    private fun enterLevel(s: PropagationState, v: Int) {
        s.levelToDecisionVar.add(s.problem.numBoolVars + v)
        s.currentLevel = s.levelToDecisionVar.size
        s.currentFactor = 0
    }

    private fun assertStatesEquivalent(seq: PropagationState, batch: PropagationState, where: String) {
        assertEquals(seq.intDomains[0], batch.intDomains[0], "domain mismatch at $where")
        assertEquals(seq.atoms.intVar.size, batch.atoms.intVar.size)
        for (id in 0 until seq.atoms.intVar.size) {
            assertEquals(
                seq.atomCurrentTruth(id),
                batch.atomCurrentTruth(id),
                "atom truth mismatch at $where: id=$id kind=${seq.atoms.kind[id]} k=${seq.atoms.threshold[id]} " +
                    "seqDom=${seq.intDomains[0]} batchDom=${batch.intDomains[0]}",
            )
            assertEquals(
                seq.atoms.lvl[id],
                batch.atoms.lvl[id],
                "atom level mismatch at $where: id=$id kind=${seq.atoms.kind[id]} k=${seq.atoms.threshold[id]}",
            )
        }
    }

    @Test
    fun `interior batch exclusions should keep per-value reasons for early and late atoms`() {
        val s = freshState(2, 9)
        enterLevel(s, 1)
        check(s.tightenIntMax(1, 4))
        val firstReason = intArrayOf(Lit.make(s.atomVarLe(1, 4), false))
        val secondReason = intArrayOf(Lit.make(s.atomVarLe(1, 5), false))
        val first = s.atomVarEq(0, 1)
        val second = s.atomVarEq(0, 3)
        val mark = s.mark()
        val before = s.undo.size

        assertTrue(
            s.excludeInteriorIntValues(0, longArrayOf(1, 3, 5)) { value ->
                if (value == 3L) secondReason else firstReason
            },
        )

        val late = s.atomVarEq(0, 5)
        assertEquals(firstReason.toList(), assertNotNull(s.atomAntecedentsDerived(first)).toList())
        assertEquals(secondReason.toList(), assertNotNull(s.atomAntecedentsDerived(second)).toList())
        assertEquals(firstReason.toList(), assertNotNull(s.atomAntecedentsDerived(late)).toList())
        assertTrue(s.inDomainAt(0, 3, before))
        s.undoTo(mark)
        for (value in longArrayOf(1, 3, 5)) {
            assertTrue(value in s.intDomains[0])
            assertFalse(s.holeHistHas(0, value))
        }
    }

    @Test
    fun `batch exclude should reach the same state as sequential excludes`() {
        val hi = 14
        val rng = Random(0xBA7C4)
        repeat(100) { trial ->
            val picked = mutableSetOf<Int>()
            val k = rng.nextInt(0, hi + 1)
            repeat(k) { picked.add(rng.nextInt(0, hi + 1)) }
            if (picked.size > hi) picked.remove(picked.first())
            val values = picked.map { it.toLong() }.toLongArray().also { it.sort() }

            val seq = freshState(1, hi)
            materializeAllAtoms(seq, 1, hi)
            enterLevel(seq, 0)
            for (v in values) seq.excludeIntValue(0, v)

            val batch = freshState(1, hi)
            materializeAllAtoms(batch, 1, hi)
            enterLevel(batch, 0)
            batch.excludeIntValues(0, values, null)

            assertStatesEquivalent(seq, batch, "trial=$trial values=${values.toList()}")
        }
    }

    @Test
    fun `batch exclude of every remaining value should report a conflict`() {
        val s = freshState(1, 4)
        materializeAllAtoms(s, 1, 4)
        enterLevel(s, 0)
        assertEquals(false, s.excludeIntValues(0, longArrayOf(0, 1, 2, 3, 4), null))
    }

    @Test
    fun `batch exclude should keep atom truth consistent across push and pop`() {
        val numVars = 3
        val hi = 9
        val rng = Random(0x5eed5)
        repeat(4) { trial ->
            val s = freshState(numVars, hi)
            materializeAllAtoms(s, numVars, hi)
            val marks = ArrayDeque<PropagationState.LevelMark>()
            marks.addLast(s.mark())
            repeat(30) { step ->
                val pop = marks.size > 1 && rng.nextInt(3) == 0
                if (pop) {
                    val target = rng.nextInt(0, marks.size - 1)
                    while (marks.size > target + 1) marks.removeLast()
                    s.undoTo(marks.last())
                } else {
                    val v = rng.nextInt(numVars)
                    val d = s.intDomains[v]
                    if (d.min != d.max) {
                        enterLevel(s, v)
                        val picked = mutableSetOf<Long>()
                        val live = ArrayList<Long>()
                        d.values.forEach { live.add(it) }
                        val take = rng.nextInt(1, live.size)
                        repeat(take) { picked.add(live[rng.nextInt(live.size)]) }
                        if (picked.size >= live.size) picked.remove(picked.first())
                        val values = picked.toLongArray().also { it.sort() }
                        if (s.excludeIntValues(v, values, null)) marks.addLast(s.mark())
                    }
                }
                for (id in 0 until s.atoms.intVar.size) {
                    val v = s.atoms.intVar[id]
                    assertEquals(
                        s.atomTruthOf(v, s.atoms.kind[id], s.atoms.threshold[id]),
                        s.atomCurrentTruth(id),
                        "trial=$trial step=$step atom=$id",
                    )
                }
            }
        }
    }

    private fun newState(numBoolVars: Int): PropagationState {
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        return PropagationState(problem, Assumptions.None)
    }

    @Test
    fun `indices across a word boundary should stay independent`() {
        // 130 vars crosses two LongArray word boundaries; verify no bleed.
        val s = newState(130)
        s.boolValues[63] = true
        s.boolValues[64] = false
        s.boolValues[129] = true
        assertEquals(true, s.boolValues[63])
        assertEquals(false, s.boolValues[64])
        assertNull(s.boolValues[65])
        assertEquals(true, s.boolValues[129])
        assertNull(s.boolValues[128])
    }

    // One static factor so learned-clause ids start above 0.
    private fun emptyState(numBool: Int): PropagationState {
        val p = Problem(
            numBoolVars = numBool,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        return PropagationState(p, Assumptions.None)
    }

    @Test
    fun `noteLearnedUse should mark the clause used until cleared`() {
        val s = emptyState(3)
        val fid = s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), lbd = 4)
        val idx = fid - s.problem.numFactors
        s.noteLearnedUse(fid)
        assertTrue(s.learnedClauseUsedSinceReduction(idx))
        s.clearLearnedClauseUsed(idx)
        assertFalse(s.learnedClauseUsedSinceReduction(idx))
        s.noteLearnedUse(0)
        assertFalse(s.learnedClauseUsedSinceReduction(idx))
    }

    @Test
    fun `forget should compact tier and used flags in lockstep with surviving clauses`() {
        val s = emptyState(4)
        val f0 = s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), lbd = 2)
        val f1 = s.addLearnedClause(Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))), lbd = 5)
        val f2 = s.addLearnedClause(Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))), lbd = 9)
        val base = s.problem.numFactors
        s.setLearnedClauseTier(f0 - base, ClauseTier.CORE)
        s.setLearnedClauseTier(f1 - base, ClauseTier.MID)
        s.setLearnedClauseTier(f2 - base, ClauseTier.LOCAL)
        s.noteLearnedUse(f2)

        s.forgetLearnedClauses { idx, _ -> idx != 1 }

        assertEquals(2, s.learnedClauses.size)
        assertEquals(ClauseTier.CORE, s.learnedClauseTier(0))
        assertFalse(s.learnedClauseUsedSinceReduction(0))
        assertEquals(ClauseTier.LOCAL, s.learnedClauseTier(1))
        assertTrue(s.learnedClauseUsedSinceReduction(1))
    }

    private fun state(): PropagationState {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            // Two static factors so learned-clause ids start at numFactors = 2.
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )
        return PropagationState(p, Assumptions.None)
    }

    @Test
    fun `forget should retain referenced reasons and remap their factor ids`() {
        val s = state()
        val base = s.problem.numFactors // 2
        val fids = IntArray(4) { i ->
            s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make((i % 4), false))), lbd = 2)
        }
        s.boolReason[0] = fids[3]
        s.boolReason[1] = fids[2]
        s.intMinReason[0] = fids[3]
        s.intMaxReason[0] = fids[2]
        s.boolReason[2] = 0

        s.forgetLearnedClauses { idx, _ -> idx == 0 || idx == 3 }

        assertEquals(base + 2, s.boolReason[0])
        assertEquals(base + 1, s.boolReason[1])
        assertEquals(base + 2, s.intMinReason[0])
        assertEquals(base + 1, s.intMaxReason[0])
        assertEquals(0, s.boolReason[2])

        s.factorAt(s.boolReason[0])
        s.factorAt(s.intMinReason[0])
    }

    private fun scan(s: PropagationSession, weights: LongArray): Long {
        var total = 0L
        for (b in weights.indices) {
            val v = s.boolValue(b)
            total += when {
                v == true -> weights[b]
                v == false -> 0L
                weights[b] < 0L -> weights[b]
                else -> 0L
            }
        }
        return total
    }

    @Test
    fun `the incremental bound should match the full scan across pins and backtracks`() {
        val weights = longArrayOf(5, -3, 2, -7, 4)
        val p = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertTrue(s.installObjectiveBoolBound(weights))

        s.pinBool(0, true)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "after a propagating pin")
        s.pinBool(3, true)
        s.popToLevel(1)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "after backtrack")
        s.pinBool(3, false)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "after redescend to a new value")
        s.popToLevel(0)
        assertEquals(weights.filter { it < 0L }.sum(), s.objectiveBoolLowerBound(), "back at root")
    }

    @Test
    fun `install should be declined below the root`() {
        val p = Problem(2, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        assertFalse(s.installObjectiveBoolBound(longArrayOf(3, 4)))
    }
}
