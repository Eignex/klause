package com.eignex.klause.schema

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs as kabs
import kotlin.math.min as kmin

class BuildersTest {

    @Test
    fun `three ints all different in samples`() {
        class S : VariableSchema() {
            val a by intVar(min = 1, max = 3)
            val b by intVar(min = 1, max = 3)
            val c by intVar(min = 1, max = 3)
            val unique by constraint { allDifferent(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()

        assertTrue(compiled.problem.factors.any { it is AllDifferent })
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 4)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            val cv = compiled.decode(schema.c, s)
            assertTrue(setOf(av, bv, cv).size == 3, "duplicates: a=$av b=$bv c=$cv")
        }
    }

    @Test
    fun `gcc rejects negative range`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to -2..-1)) }
        }
        assertFails { S() }
    }

    @Test
    fun `gcc rejects range exceeding var count`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { gcc(listOf(a, b), mapOf(0 to 0..5)) }
        }
        assertFails { S() }
    }

    @Test
    fun `all different pigeonhole rejected`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 1)
            val b by intVar(min = 0, max = 1)
            val c by intVar(min = 0, max = 1)

            val pin by constraint { allDifferent(a, b, c) }
        }
        assertFails { S() }
    }

    @Test
    fun `table tuple out of domain rejected`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { table(listOf(a, b), listOf(listOf(5, 1))) }
        }
        assertFails { S() }
    }

    @Test
    fun `not table tuple out of domain rejected`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val pin by constraint { notTable(listOf(a, b), listOf(listOf(0, 99))) }
        }
        assertFails { S() }
    }

    @Test
    fun `positive table forces one of allowed tuples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 3)
            val y by intVar(min = 0, max = 3)
            val rel by constraint {
                table(listOf(x, y), listOf(listOf(0, 1), listOf(2, 2), listOf(3, 0)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 11)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        val allowed = setOf(0L to 1L, 2L to 2L, 3L to 0L)
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue((xv to yv) in allowed, "($xv, $yv) not in allowed table")
        }
    }

    @Test
    fun `negative table forbids listed tuples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 2)
            val y by intVar(min = 0, max = 2)

            val rel by constraint {
                notTable(listOf(x, y), listOf(listOf(1, 1), listOf(2, 2)))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 5)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        val forbidden = setOf(1L to 1L, 2L to 2L)
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue((xv to yv) !in forbidden, "($xv, $yv) should be forbidden")
        }
    }

    @Test
    fun `multi value gcc bounds hold in samples`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val c by intVar(min = 0, max = 2)
            val d by intVar(min = 0, max = 2)
            val counts by constraint {
                gcc(listOf(a, b, c, d), mapOf(0 to 1..2, 1 to 1..2, 2 to 0..2))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 14)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val vs = listOf(schema.a, schema.b, schema.c, schema.d).map { compiled.decode(it, s) }
            val c0 = vs.count { it == 0L }
            val c1 = vs.count { it == 1L }
            val c2 = vs.count { it == 2L }
            assertTrue(c0 in 1..2, "value 0 count=$c0, vs=$vs")
            assertTrue(c1 in 1..2, "value 1 count=$c1, vs=$vs")
            assertTrue(c2 in 0..2, "value 2 count=$c2, vs=$vs")
        }
    }

    @Test
    fun `gcc can force exact distribution`() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 2)
            val b by intVar(min = 0, max = 2)
            val c by intVar(min = 0, max = 2)
            val perm by constraint { gcc(listOf(a, b, c), mapOf(0 to 1..1, 1 to 1..1, 2 to 1..1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 33)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val vs = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            assertTrue(vs.toSet() == setOf(0L, 1L, 2L), "not a permutation: $vs")
        }
    }

    @Test
    fun `element over int vars picks constrained item`() {
        class S : VariableSchema() {
            val idx by intVar(min = 0, max = 2)
            val a by intVar(min = 0, max = 9)
            val b by intVar(min = 0, max = 9)
            val c by intVar(min = 0, max = 9)
            val pin by constraint { element(idx, listOf(a, b, c)) eq 7 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 5)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val i = compiled.decode(schema.idx, s)
            val items = listOf(schema.a, schema.b, schema.c).map { compiled.decode(it, s) }
            assertTrue(i in items.indices)
            assertTrue(
                items[i.toInt()] == 7L,
                "items[$i]=${items[i.toInt()]}, expected 7 (a=${items[0]} b=${items[1]} c=${items[2]})",
            )
        }
    }

    @Test
    fun `circuit DSL emits a Circuit factor`() {
        class S : VariableSchema() {
            val s0 by intVar(min = 0, max = 3)
            val s1 by intVar(min = 0, max = 3)
            val s2 by intVar(min = 0, max = 3)
            val s3 by intVar(min = 0, max = 3)
            val cyc by constraint { circuit(s0, s1, s2, s3) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Circuit })
        val solver = BacktrackSolver(compiled.problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample)
        // Verify it's a Hamiltonian cycle of length 4.
        val visited = BooleanArray(4)
        var node = 0
        for (step in 0 until 4) {
            assertTrue(!visited[node], "revisit at step $step")
            visited[node] = true
            node = sample.ints[node].toInt()
        }
        assertEquals(0, node, "must close the cycle")
    }

    @Test
    fun `circuit DSL with 1-indexed offset channels through aux vars`() {
        // Successors live in [1, 4] (MiniZinc-style). Compiler should add channeling.
        class S : VariableSchema() {
            val s0 by intVar(min = 1, max = 4)
            val s1 by intVar(min = 1, max = 4)
            val s2 by intVar(min = 1, max = 4)
            val s3 by intVar(min = 1, max = 4)
            val cyc by constraint { circuit(listOf(s0, s1, s2, s3), valueOffset = 1) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Circuit })
        val solver = BacktrackSolver(compiled.problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample)
        // The user-facing values are 1..4 (1-indexed). Verify Hamiltonian cycle in that space.
        val visited = BooleanArray(4)
        var node = 0 // 0-indexed entry point
        for (step in 0 until 4) {
            assertTrue(!visited[node], "revisit at step $step in ${sample.ints.toList()}")
            visited[node] = true
            // Successor value (1-indexed) → 0-indexed node.
            node = sample.ints[node].toInt() - 1
        }
        assertEquals(0, node, "must close the cycle (1-indexed inputs decoded)")
    }

    @Test
    fun `cumulative DSL emits a Cumulative factor`() {
        class S : VariableSchema() {
            val t0 by intVar(min = 0, max = 4)
            val t1 by intVar(min = 0, max = 4)
            val t2 by intVar(min = 0, max = 4)
            val cap by constraint {
                cumulative(
                    starts = listOf(t0, t1, t2),
                    durations = listOf(2, 2, 2),
                    resources = listOf(1, 1, 1),
                    capacity = 1,
                )
            }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Cumulative })
        val solver = BacktrackSolver(compiled.problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample)
        // Verify non-overlapping schedule under capacity 1.
        val occ = IntArray(8)
        for (i in 0 until 3) {
            val s = sample.ints[i].toInt()
            for (t in s until s + 2) if (t in occ.indices) occ[t]++
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "capacity broken at t=$t: ${sample.ints.toList()}")
    }

    @Test
    fun `disjunctive DSL emits a unary Cumulative factor`() {
        class S : VariableSchema() {
            val t0 by intVar(min = 0, max = 2)
            val t1 by intVar(min = 0, max = 2)
            val t2 by intVar(min = 0, max = 2)
            val nonOverlap by constraint {
                disjunctive(listOf(t0, t1, t2), listOf(1, 1, 1))
            }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Cumulative && it.unary })
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).toList()
        assertEquals(6, samples.size, "expected 3! disjunctive schedules; got $samples")
    }

    @Test
    fun `channel links int to one hot booleans`() {
        class S : VariableSchema() {
            val idx by intVar(min = 0, max = 3)
            val b0 by boolVar()
            val b1 by boolVar()
            val b2 by boolVar()
            val b3 by boolVar()
            val link by constraint { channel(idx, listOf(b0, b1, b2, b3)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 21)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val i = compiled.decode(schema.idx, s)
            val flags = listOf(schema.b0, schema.b1, schema.b2, schema.b3).map { compiled.decode(it, s) }
            for (j in flags.indices) {
                assertTrue(flags[j] == (i == j.toLong()), "i=$i flags=$flags mismatch at j=$j")
            }
        }
    }

    @Test
    fun `lex leq holds in samples`() {
        class S : VariableSchema() {
            val a0 by intVar(min = 0, max = 3)
            val a1 by intVar(min = 0, max = 3)
            val b0 by intVar(min = 0, max = 3)
            val b1 by intVar(min = 0, max = 3)
            val ord by constraint { lexLeq(listOf(a0, a1), listOf(b0, b1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 8)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = listOf(compiled.decode(schema.a0, s), compiled.decode(schema.a1, s))
            val b = listOf(compiled.decode(schema.b0, s), compiled.decode(schema.b1, s))
            val ok = a[0] < b[0] || (a[0] == b[0] && a[1] <= b[1])
            assertTrue(ok, "a=$a b=$b violates lexLeq")
        }
    }

    @Test
    fun `lex lt forces strict order`() {
        class S : VariableSchema() {
            val a0 by intVar(min = 0, max = 2)
            val a1 by intVar(min = 0, max = 2)
            val b0 by intVar(min = 0, max = 2)
            val b1 by intVar(min = 0, max = 2)
            val ord by constraint { lexLt(listOf(a0, a1), listOf(b0, b1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 12)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = listOf(compiled.decode(schema.a0, s), compiled.decode(schema.a1, s))
            val b = listOf(compiled.decode(schema.b0, s), compiled.decode(schema.b1, s))
            val ok = a[0] < b[0] || (a[0] == b[0] && a[1] < b[1])
            assertTrue(ok, "a=$a b=$b not strictly lex-less")
        }
    }

    @Test
    fun `increasing holds in samples`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 3)
            val x1 by intVar(min = 0, max = 3)
            val x2 by intVar(min = 0, max = 3)
            val ord by constraint { increasing(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 5)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val v = listOf(compiled.decode(schema.x0, s), compiled.decode(schema.x1, s), compiled.decode(schema.x2, s))
            assertTrue(v[0] <= v[1] && v[1] <= v[2], "v=$v not non-decreasing")
        }
    }

    @Test
    fun `strictlyIncreasing forces strict order`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 4)
            val x1 by intVar(min = 0, max = 4)
            val x2 by intVar(min = 0, max = 4)
            val ord by constraint { strictlyIncreasing(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 9)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val v = listOf(compiled.decode(schema.x0, s), compiled.decode(schema.x1, s), compiled.decode(schema.x2, s))
            assertTrue(v[0] < v[1] && v[1] < v[2], "v=$v not strictly increasing")
        }
    }

    @Test
    fun `decreasing holds in samples`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 3)
            val x1 by intVar(min = 0, max = 3)
            val x2 by intVar(min = 0, max = 3)
            val ord by constraint { decreasing(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 7)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val v = listOf(compiled.decode(schema.x0, s), compiled.decode(schema.x1, s), compiled.decode(schema.x2, s))
            assertTrue(v[0] >= v[1] && v[1] >= v[2], "v=$v not non-increasing")
        }
    }

    @Test
    fun `xor emits odd parity factor`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val parity by constraint { xor(a, b, c) }
        }
        val compiled = S().compile()
        val xf = compiled.problem.factors.single { it is Xor } as Xor
        assertTrue(xf.targetParity == 1)
    }

    @Test
    fun `xor odd parity holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val odd by constraint { xor(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c).count { compiled.decode(it, s) }
            assertTrue(count % 2 == 1, "count=$count")
        }
    }

    @Test
    fun `negated xor enforces even parity`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val even by constraint { !xor(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 19)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c).count { compiled.decode(it, s) }
            assertTrue(count % 2 == 0, "count=$count")
        }
    }

    @Test
    fun `pb at most emits factor at top level`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val cap by constraint { pbAtMost(listOf(3, 2, 5), listOf(a, b, c), 4) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is PseudoBoolean })
    }

    @Test
    fun `pb at most holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()

            val cap by constraint { pbAtMost(listOf(3, 2, 5, 1), listOf(a, b, c, d), 6) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 31)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = if (compiled.decode(schema.a, s)) 3 else 0
            val bv = if (compiled.decode(schema.b, s)) 2 else 0
            val cv = if (compiled.decode(schema.c, s)) 5 else 0
            val dv = if (compiled.decode(schema.d, s)) 1 else 0
            assertTrue(av + bv + cv + dv <= 6, "sum=${av + bv + cv + dv}")
        }
    }

    @Test
    fun `pb at least holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()

            val req by constraint { pbAtLeast(listOf(2, 3, 4), listOf(a, b, c), 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 13)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val sum = (if (compiled.decode(schema.a, s)) 2 else 0) +
                (if (compiled.decode(schema.b, s)) 3 else 0) +
                (if (compiled.decode(schema.c, s)) 4 else 0)
            assertTrue(sum >= 5, "sum=$sum")
        }
    }

    @Test
    fun `pb exactly holds in samples`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()

            val pin by constraint { pbExactly(listOf(2, 3, 5), listOf(a, b, c), 5) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 41)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val sum = (if (compiled.decode(schema.a, s)) 2 else 0) +
                (if (compiled.decode(schema.b, s)) 3 else 0) +
                (if (compiled.decode(schema.c, s)) 5 else 0)
            assertTrue(sum == 5, "sum=$sum")
        }
    }

    @Test
    fun `pb reified under implies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val rule by constraint { flag implies pbAtMost(listOf(2, 3, 4), listOf(a, b, c), 4) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedPseudoBoolean })
    }

    @Test
    fun `min of two ints samples validly`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val capMin by constraint { min(x, y) le 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 11)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(kmin(xv, yv) <= 2, "min($xv,$yv)>2")
        }
    }

    @Test
    fun `max of three ints samples validly`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val z by intVar(min = 0, max = 4)
            val capMax by constraint { max(x, y, z) ge 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 7)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            val zv = compiled.decode(schema.z, s)
            assertTrue(maxOf(xv, yv, zv) >= 3, "max($xv,$yv,$zv)<3")
        }
    }

    @Test
    fun `abs of signed int samples validly`() {
        class S : VariableSchema() {
            val x by intVar(min = -5, max = 5)
            val capAbs by constraint { abs(x) le 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 23)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(kabs(xv) <= 2, "|$xv|>2")
        }
    }

    @Test
    fun `if then else dispatches by condition`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 9)
            val y by intVar(min = 0, max = 9)
            val pin by constraint { ifThenElse(flag, x, y) eq 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 42)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val flag = compiled.decode(schema.flag, s)
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            val picked = if (flag) xv else yv
            assertTrue(picked == 5L, "flag=$flag x=$xv y=$yv selected=$picked, expected 5")
        }
    }

    @Test
    fun `if then else inside arithmetic`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)

            val cap by constraint { ifThenElse(flag, x, y) le 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 99)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val flag = compiled.decode(schema.flag, s)
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            val picked = if (flag) xv else yv
            assertTrue(picked <= 2, "selected=$picked > 2")
        }
    }

    @Test
    fun `sort - ys is the ascending permutation of xs`() {
        class S : VariableSchema() {
            val x0 by intVar(1, 3)
            val x1 by intVar(1, 3)
            val y0 by intVar(1, 3)
            val y1 by intVar(1, 3)
            val rule by constraint { sort(listOf(x0, x1), listOf(y0, y1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem)
            .enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "sort: solver found no sample")
        fun v(n: String) = sample.ints[compiled.intVarIdByName.getValue(n)]
        val xs = listOf(v("x0"), v("x1")).sorted()
        val ys = listOf(v("y0"), v("y1"))
        assertTrue(ys[0] <= ys[1], "sort: ys not ascending: $ys")
        assertTrue(ys == xs, "sort: ys ($ys) != sorted xs ($xs)")
    }

    @Test
    fun `diffn - two unit squares do not overlap`() {
        class S : VariableSchema() {
            val x0 by intVar(0, 2)
            val y0 by intVar(0, 2)
            val x1 by intVar(0, 2)
            val y1 by intVar(0, 2)
            val rule by constraint {
                diffn(listOf(x0, x1), listOf(y0, y1), listOf(1, 1), listOf(1, 1))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem)
            .enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "diffn: solver found no sample")
        fun v(n: String) = sample.ints[compiled.intVarIdByName.getValue(n)]
        val ax = v("x0")
        val ay = v("y0")
        val bx = v("x1")
        val by = v("y1")
        val disjoint = ax + 1 <= bx || bx + 1 <= ax || ay + 1 <= by || by + 1 <= ay
        assertTrue(disjoint, "diffn: rectangles overlap at ($ax,$ay) ($bx,$by)")
    }

    @Test
    fun `regular - DFA accepts the sequence`() {
        // 2 states, 2 symbols. From any state, symbol 1 -> state 1, symbol 2 -> state 2.
        // Accept only in state 1 => the FINAL symbol must be 1 (final state = last symbol).
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val s1 by intVar(1, 2)
            val rule by constraint {
                regular(
                    seq = listOf(s0, s1),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = listOf(
                        1,
                        2, // from state 1: sym1->1, sym2->2
                        1,
                        2, // from state 2: sym1->1, sym2->2
                    ),
                    q0 = 1,
                    accepting = listOf(1),
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem)
            .enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "regular: solver found no sample")
        fun v(n: String) = sample.ints[compiled.intVarIdByName.getValue(n)]
        assertTrue(v("s1") == 1L, "regular: final symbol not accepting (s1=${v("s1")})")
    }

    @Test
    fun `mdd - 2-layer MDD accepts a sample`() {
        // Layer 0: state 0 -> seq[0] = 1 -> state 0 (or 0 -> 2 -> 1).
        // Layer 1: state 0 -> seq[1] = 1 -> accept (state 0); state 1 -> 2 -> accept.
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val s1 by intVar(1, 2)
            val rule by constraint {
                mdd(
                    seq = listOf(s0, s1),
                    numStatesPerLayer = listOf(1, 2, 1),
                    layerStarts = listOf(0, 6, 12),
                    transitions = listOf(
                        0, 1, 0, // layer 0 trans 1
                        0, 2, 1, // layer 0 trans 2
                        0, 1, 0, // layer 1 trans 1
                        1, 2, 0, // layer 1 trans 2
                    ),
                    initial = 0,
                    accepting = listOf(0),
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "mdd: solver found no sample")
    }

    @Test
    fun `cost_regular - 1-step weighted DFA`() {
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val cost by intVar(0, 100)
            val rule by constraint {
                costRegular(
                    seq = listOf(s0),
                    numStates = 2,
                    numSymbols = 2,
                    // Row-major Q×S; dst-state is 1-based (0 = no transition).
                    transitions = listOf(
                        1,
                        2, // from state 0
                        1,
                        2, // from state 1
                    ),
                    weights = listOf(
                        3,
                        5,
                        7,
                        11,
                    ),
                    initial = 0,
                    accepting = listOf(0, 1),
                    cost = cost,
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "cost_regular: solver found no sample")
    }

    @Test
    fun `cost_mdd - weighted layer accumulation`() {
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val cost by intVar(0, 100)
            val rule by constraint {
                costMdd(
                    seq = listOf(s0),
                    numStatesPerLayer = listOf(1, 1),
                    layerStarts = listOf(0, 8),
                    transitions = listOf(
                        0,
                        1,
                        0,
                        3,
                        0,
                        2,
                        0,
                        7,
                    ),
                    initial = 0,
                    accepting = listOf(0),
                    cost = cost,
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "cost_mdd: solver found no sample")
    }

    @Test
    fun `symmetricAllDifferent enumerates exactly the involutions`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 2)
            val x1 by intVar(min = 0, max = 2)
            val x2 by intVar(min = 0, max = 2)
            val rows by constraint { symmetricAllDifferent(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val handles = listOf(schema.x0, schema.x1, schema.x2)
        val sols = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { s -> handles.map { compiled.decode(it, s) } }.toList()
        // Every solution is a self-inverse permutation of {0,1,2}.
        for (p in sols) {
            assertEquals(p.toSet().size, p.size, "not a permutation: $p")
            for (i in p.indices) assertEquals(i.toLong(), p[p[i].toInt()], "not an involution at $i: $p")
        }
        // S3 has 4 involutions: identity plus the three transpositions.
        assertEquals(4, sols.toSet().size, "expected 4 involutions, got ${sols.toSet()}")
    }

    @Test
    fun `inverse enumerates exactly the permutation-inverse pairs`() {
        class S : VariableSchema() {
            val f0 by intVar(min = 0, max = 2)
            val f1 by intVar(min = 0, max = 2)
            val f2 by intVar(min = 0, max = 2)
            val g0 by intVar(min = 0, max = 2)
            val g1 by intVar(min = 0, max = 2)
            val g2 by intVar(min = 0, max = 2)
            val rows by constraint { inverse(listOf(f0, f1, f2), listOf(g0, g1, g2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val fh = listOf(schema.f0, schema.f1, schema.f2)
        val gh = listOf(schema.g0, schema.g1, schema.g2)
        val sols = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { s -> fh.map { compiled.decode(it, s) } to gh.map { compiled.decode(it, s) } }.toList()
        for ((f, g) in sols) {
            assertTrue(f.toSet().size == f.size, "f not a permutation: $f")
            for (i in f.indices) assertEquals(i.toLong(), g[f[i].toInt()], "g is not the inverse of f at $i: f=$f g=$g")
        }
        // Each of the 6 permutations of {0,1,2} pairs with a unique inverse.
        assertEquals(6, sols.size, "expected 6 permutation/inverse pairs, got ${sols.size}")
    }
}
