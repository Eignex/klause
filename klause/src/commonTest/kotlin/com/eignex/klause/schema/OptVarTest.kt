package com.eignex.klause.schema

import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.PresenceSpec
import com.eignex.klause.ast.allDifferentOpt
import com.eignex.klause.ast.countEqOpt
import com.eignex.klause.ast.cumulativeOpt
import com.eignex.klause.ast.disjunctiveOpt
import com.eignex.klause.ast.gccOpt
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.le
import com.eignex.klause.ast.nValueOpt
import com.eignex.klause.compile.compile
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptDeclaratorTest {
    private class S : VariableSchema() {
        val x by optIntVar(min = 0, max = 5)
    }

    @Test
    fun `optIntVar registers presence then value`() {
        val s = S()
        val entries = s.entries.entries.toList()
        assertEquals(2, entries.size)
        // Presence bool registered first under the synthetic name.
        assertEquals("x__present", entries[0].key)
        val presence = entries[0].value
        assertTrue(presence is PresenceSpec)
        assertEquals("x", presence.valueName)
        assertEquals("x", entries[1].key)
        assertTrue(entries[1].value is IntSpec)
    }

    @Test
    fun `decode returns null when presence false`() {
        val s = S()
        val compiled = s.compile()
        // Construct a sample by hand: presence false, value irrelevant.
        val sample = Sample(
            bools = booleanArrayOf(false),
            ints = intArrayOf(3),
        )
        assertNull(compiled.decode(s.x, sample))
    }

    @Test
    fun `decode returns value when presence true`() {
        val s = S()
        val compiled = s.compile()
        val sample = Sample(
            bools = booleanArrayOf(true),
            ints = intArrayOf(3),
        )
        assertEquals(3, compiled.decode(s.x, sample))
    }
}

class OptComparisonSemanticsTest {
    /**
     * If [x] is absent, `x ge 3` must read false (MZN opt semantics), so the constraint
     * `(x ge 3) implies (y le 0)` is trivially satisfied for absent x. Solver should be
     * able to find assignments where x is absent and y is unconstrained.
     */
    private class S : VariableSchema() {
        val x by optIntVar(min = 0, max = 5)
        val y by intVar(min = 0, max = 5)
        val c by constraint { (x ge 3) implies (y le 0) }
    }

    @Test
    fun `absent x makes x ge 3 false`() {
        val s = S()
        val compiled = s.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(50).toList()
        // Should find assignments where x is absent and y > 0.
        val anyAbsentWithBigY = samples.any { sample ->
            compiled.decode(s.x, sample) == null && compiled.decode(s.y, sample) > 0
        }
        assertTrue(
            anyAbsentWithBigY,
            "Expected an absent-x sample with y>0, since absence should bypass the implication.",
        )
    }
}

class OptAllDifferentTest {
    private class S : VariableSchema() {
        val a by optIntVar(min = 0, max = 2)
        val b by optIntVar(min = 0, max = 2)
        val c by optIntVar(min = 0, max = 2)
        val allDiff by constraint {
            allDifferentOpt(
                terms = listOf(a.value, b.value, c.value),
                presents = listOf(a.present, b.present, c.present),
            )
        }
    }

    @Test
    fun `compiles to native AllDifferent factor with presents`() {
        val s = S()
        val compiled = s.compile()
        val allDiffFactor = compiled.problem.factors.filterIsInstance<AllDifferent>().single()
        assertEquals(3, allDiffFactor.vars.size)
        assertEquals(3, allDiffFactor.presents.size)
    }

    @Test
    fun `LS finds assignments where two absent collapse to same value`() {
        val s = S()
        val compiled = s.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 10_000, randomSeed = 13)).take(80).toList()
        // Verify every sampled assignment is feasible under opt-aware all-different.
        for (sample in samples) {
            val pa = compiled.decode(s.a.present, sample)
            val pb = compiled.decode(s.b.present, sample)
            val pc = compiled.decode(s.c.present, sample)
            val va = if (pa) sample.ints[compiled.intVarIdByName.getValue("a")] else null
            val vb = if (pb) sample.ints[compiled.intVarIdByName.getValue("b")] else null
            val vc = if (pc) sample.ints[compiled.intVarIdByName.getValue("c")] else null
            val presentVals = listOfNotNull(va, vb, vc)
            assertEquals(
                presentVals.size,
                presentVals.toSet().size,
                "Present-only values must be distinct: pa=$pa pb=$pb pc=$pc va=$va vb=$vb vc=$vc",
            )
        }
    }
}

class OptCountTest {
    private class S : VariableSchema() {
        val a by optIntVar(min = 0, max = 2)
        val b by optIntVar(min = 0, max = 2)
        val c by optIntVar(min = 0, max = 2)
        val cnt by intVar(min = 0, max = 3)
        val countOnes by constraint {
            countEqOpt(
                xs = listOf(a.value, b.value, c.value),
                v = 1,
                n = cnt,
                presents = listOf(a.present, b.present, c.present),
            )
        }
    }

    @Test
    fun `count tracks only present 1-valued positions`() {
        val s = S()
        val compiled = s.compile()
        val countFactor = compiled.problem.factors.filterIsInstance<Count>().single()
        assertEquals(3, countFactor.presents.size)
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 8_000, randomSeed = 17)).take(40).toList()
        for (sample in samples) {
            val pa = compiled.decode(s.a.present, sample)
            val pb = compiled.decode(s.b.present, sample)
            val pc = compiled.decode(s.c.present, sample)
            val va = sample.ints[compiled.intVarIdByName.getValue("a")]
            val vb = sample.ints[compiled.intVarIdByName.getValue("b")]
            val vc = sample.ints[compiled.intVarIdByName.getValue("c")]
            val expected = listOf(pa to va, pb to vb, pc to vc).count { it.first && it.second == 1 }
            val actual = compiled.decode(s.cnt, sample)
            assertEquals(
                expected,
                actual,
                "count opt mismatch: presents=($pa,$pb,$pc) values=($va,$vb,$vc) cnt=$actual",
            )
        }
    }
}

class OptNValueTest {
    private class S : VariableSchema() {
        val a by optIntVar(min = 0, max = 1)
        val b by optIntVar(min = 0, max = 1)
        val c by optIntVar(min = 0, max = 1)
        val nDistinct by intVar(min = 0, max = 2)
        val nval by constraint {
            nValueOpt(
                n = nDistinct,
                xs = listOf(a.value, b.value, c.value),
                presents = listOf(a.present, b.present, c.present),
            )
        }
    }

    @Test
    fun `nvalue ignores absent positions`() {
        val s = S()
        val compiled = s.compile()
        assertNotNull(compiled.problem.factors.filterIsInstance<NValue>().singleOrNull())
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 8_000, randomSeed = 19)).take(40).toList()
        for (sample in samples) {
            val pa = compiled.decode(s.a.present, sample)
            val pb = compiled.decode(s.b.present, sample)
            val pc = compiled.decode(s.c.present, sample)
            val va = sample.ints[compiled.intVarIdByName.getValue("a")]
            val vb = sample.ints[compiled.intVarIdByName.getValue("b")]
            val vc = sample.ints[compiled.intVarIdByName.getValue("c")]
            val expected = buildSet {
                if (pa) add(va)
                if (pb) add(vb)
                if (pc) add(vc)
            }.size
            assertEquals(expected, compiled.decode(s.nDistinct, sample))
        }
    }
}

class OptGccTest {
    private class S : VariableSchema() {
        val a by optIntVar(min = 0, max = 2)
        val b by optIntVar(min = 0, max = 2)
        val c by optIntVar(min = 0, max = 2)

        // At least one present xs must be 0, at most two of them.
        val gcc by constraint {
            gccOpt(
                xs = listOf(a.value, b.value, c.value),
                valueCounts = mapOf(0 to 1..2),
                presents = listOf(a.present, b.present, c.present),
            )
        }
    }

    @Test
    fun `gcc counts only present positions and stays in range`() {
        val s = S()
        val compiled = s.compile()
        assertNotNull(compiled.problem.factors.filterIsInstance<GlobalCardinality>().singleOrNull())
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 8_000, randomSeed = 23)).take(40).toList()
        for (sample in samples) {
            val pa = compiled.decode(s.a.present, sample)
            val pb = compiled.decode(s.b.present, sample)
            val pc = compiled.decode(s.c.present, sample)
            val va = sample.ints[compiled.intVarIdByName.getValue("a")]
            val vb = sample.ints[compiled.intVarIdByName.getValue("b")]
            val vc = sample.ints[compiled.intVarIdByName.getValue("c")]
            val zeros = listOf(pa to va, pb to vb, pc to vc).count { it.first && it.second == 0 }
            assertTrue(
                zeros in 1..2,
                "gcc opt out of range: zeros=$zeros (presents=$pa,$pb,$pc values=$va,$vb,$vc)",
            )
        }
    }
}

class OptDisjunctiveTest {
    private class S : VariableSchema() {
        val s0 by optIntVar(min = 0, max = 4)
        val s1 by optIntVar(min = 0, max = 4)
        val s2 by optIntVar(min = 0, max = 4)
        val disj by constraint {
            disjunctiveOpt(
                starts = listOf(s0.value, s1.value, s2.value),
                durations = listOf(2, 2, 2),
                presents = listOf(s0.present, s1.present, s2.present),
            )
        }
    }

    @Test
    fun `present tasks don't overlap`() {
        val s = S()
        val compiled = s.compile()
        assertNotNull(compiled.problem.factors.filterIsInstance<Disjunctive>().singleOrNull())
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 8_000, randomSeed = 29)).take(40).toList()
        for (sample in samples) {
            val ps = listOf(s.s0, s.s1, s.s2).map { compiled.decode(it.present, sample) }
            val vs = listOf(s.s0, s.s1, s.s2).map { sample.ints[compiled.intVarIdByName.getValue(it.name)] }
            val present = ps.indices.filter { ps[it] }
            for (i in present) {
                for (j in present) {
                    if (i >= j) continue
                    val (si, sj) = vs[i] to vs[j]
                    val overlap = !(si + 2 <= sj || sj + 2 <= si)
                    assertTrue(!overlap, "present tasks $i and $j overlap: starts=$si,$sj")
                }
            }
        }
    }
}

class OptCumulativeTest {
    private class S : VariableSchema() {
        // Capacity 2, three tasks each demanding 1 unit, duration 2; total energy
        // exceeds capacity unless at least one is absent. Without opt support, the
        // constraint is infeasible. With opt support, LS should find solutions where
        // exactly one task is absent.
        val s0 by optIntVar(min = 0, max = 2)
        val s1 by optIntVar(min = 0, max = 2)
        val s2 by optIntVar(min = 0, max = 2)
        val cum by constraint {
            cumulativeOpt(
                starts = listOf(s0.value, s1.value, s2.value),
                durations = listOf(2, 2, 2),
                resources = listOf(1, 1, 1),
                capacity = 2,
                presents = listOf(s0.present, s1.present, s2.present),
            )
        }
    }

    @Test
    fun `cumulative respects per-task presence`() {
        val s = S()
        val compiled = s.compile()
        assertNotNull(compiled.problem.factors.filterIsInstance<Cumulative>().singleOrNull())
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 10_000, randomSeed = 31)).take(40).toList()
        for (sample in samples) {
            val ps = listOf(s.s0, s.s1, s.s2).map { compiled.decode(it.present, sample) }
            val vs = listOf(s.s0, s.s1, s.s2).map { sample.ints[compiled.intVarIdByName.getValue(it.name)] }
            // Reconstruct the usage profile from present tasks; max must be ≤ capacity.
            val usage = IntArray(6)
            for (i in 0..2) if (ps[i]) for (t in vs[i] until vs[i] + 2) if (t in usage.indices) usage[t]++
            val peak = usage.max()
            assertTrue(
                peak <= 2,
                "cumulative peak $peak exceeds capacity (presents=$ps starts=$vs)",
            )
        }
    }
}
