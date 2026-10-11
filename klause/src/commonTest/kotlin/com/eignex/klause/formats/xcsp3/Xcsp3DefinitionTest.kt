package com.eignex.klause.formats.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Xcsp3DefinitionTest {
    @Test
    fun `Boolean controlled products preserve source truth tables and maintained moves`() {
        for (predicate in listOf("eq(room,0)", "not(eq(room,0))")) {
            for (operand in listOf("3", "length")) {
                for (product in listOf("mul($predicate,$operand)", "mul($operand,$predicate)")) {
                    for (relation in listOf("eq(duration,$product)", "eq($product,duration)")) {
                        val parsed = Xcsp3.parse(
                            """<instance><variables><var id="room">0..2</var><var id="duration">0..3</var>
                                <var id="length">3</var></variables><constraints>
                                <intension>$relation</intension>
                                <cumulative><origins>0</origins><lengths>duration</lengths><heights>1</heights>
                                <condition>(le,1)</condition></cumulative></constraints></instance>""",
                        )
                        val source = parsed.problem
                        val sweep = assertNotNull(DefinitionalSweep.infer(source, parsed.definedVars))
                        val expected = (0L..2L).map { room ->
                            val active = if (predicate.startsWith("not")) room != 0L else room == 0L
                            listOf(room, if (active) 3L else 0L, 3L)
                        }.toSet()
                        val prepared = FinitePipeline.prepare(
                            FinitePipelineRequest(
                                source, FiniteEngine.LOCAL_SEARCH,
                                presolveConfig = PresolveConfig.parse("structural"),
                                explicitPresolveConfig = true,
                            ),
                        )
                        val posted = source.factors.filterIsInstance<Product>().single()
                        assertEquals(1, posted.result)
                        val constant = if (product == "mul($predicate,$operand)") posted.b else posted.a
                        assertEquals(3L, source.intBounds.lower(constant))
                        assertEquals(3L, source.intBounds.upper(constant))
                        assertFalse(prepared.problem.factors.any { it is Product })
                        for (problem in listOf(source, prepared.problem)) {
                            val samples = BruteForceSolver(problem.bake())
                                .enumerate(BruteForceParams(randomSeed = 0L)).toList()
                            assertEquals(expected, samples.map { prepared.reconstruct(it).ints.take(3) }.toSet())
                            val sample = samples.first { it.ints[0] == 0L }
                            val state = LocalSearchState(problem.bake(), Random(3))
                            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
                            assertTrue(state.invariants!!.isDefinedInt(1))
                            for (i in sample.ints.indices) state.assignment.setInt(i, sample.ints[i])
                            for (i in sample.bools.indices) state.assignment.setBool(i, sample.bools[i])
                            state.recompute()

                            for (room in listOf(1L, 2L, 0L)) {
                                val move = Move.IntSet(0, room)
                                val predicted = state.netDelta(move)
                                state.apply(move)
                                val active = if (predicate.startsWith("not")) room != 0L else room == 0L

                                assertEquals(if (active) 3L else 0L, state.assignment.intValue(1))
                                assertEquals(0L, predicted)
                                assertEquals(0L, state.cost)
                                state.recompute()
                                assertEquals(0L, state.cost)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `positive literal boundaries preserve exact Boolean products`() {
        for (scale in listOf(1, Int.MAX_VALUE)) {
            val parsed = Xcsp3.parse(
                """<instance><variables><var id="choice">0..1</var><var id="duration">0..$scale</var>
                    </variables><constraints><intension>eq(duration,mul(eq(choice,0),$scale))</intension>
                    </constraints></instance>""",
            )
            val problem = parsed.problem
            val baked = problem.bake()
            for (choice in 0L..1L) {
                val implied = assertIs<PropagationResult.Implied>(baked.propagate(Assumptions(ints = mapOf(0 to choice))))
                assertEquals(if (choice == 0L) scale.toLong() else 0L, implied.intValueOrNull(1))
            }
            val state = LocalSearchState(baked, Random(3))
            val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            for (i in 0 until problem.numIntVars) state.assignment.setInt(i, state.rootDomains[i].min)
            state.assignment.setInt(0, 1L)
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()
            val predicted = state.netDelta(Move.IntSet(0, 0L))

            state.apply(Move.IntSet(0, 0L))

            assertEquals(scale.toLong(), state.assignment.intValue(1))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `literal product moves respect output domains pins and implicit owners`() {
        for (predicate in listOf("eq(choice,0)", "not(eq(choice,0))")) {
            for (protection in listOf("domain", "pin", "owner", "channel pin", "Boolean pin")) {
                val upper = if (protection == "domain") 2 else 3
                val parsed = Xcsp3.parse(
                    """<instance><variables><var id="choice">0..2</var><var id="duration">0..$upper</var>
                        </variables><constraints><intension>eq(duration,mul($predicate,3))</intension>
                        </constraints></instance>""",
                )
                val problem = parsed.problem
                val channel = problem.factors.filterIsInstance<Product>().single().a
                val inactive = if (predicate.startsWith("not")) 0L else 1L
                val pins = when (protection) {
                    "pin" -> Assumptions(ints = mapOf(1 to 1L))
                    "channel pin" -> Assumptions(ints = mapOf(channel to 0L))
                    "Boolean pin" -> Assumptions(bools = mapOf(0 to (inactive == 0L)))
                    else -> Assumptions.None
                }
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), pins)
                val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
                state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
                for (i in 0 until problem.numIntVars) state.assignment.setInt(i, state.rootDomains[i].min)
                pins.forEachBool { id, value -> state.assignment.setBool(id, value) }
                state.assignment.setInt(0, inactive)
                if (protection == "pin") state.assignment.setInt(1, 1L)
                if (protection == "owner") {
                    state.moveSink.setOwners(IntArray(problem.numIntVars) { if (it == 1) 7 else -1 })
                }
                sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, pins)
                state.recompute()
                val before = state.cost
                val move = Move.IntSet(0, 1L - inactive)
                val predicted = state.netDelta(move)

                state.apply(move)

                val expected = when (protection) {
                    "domain" -> 2L
                    "pin" -> 1L
                    else -> 0L
                }
                assertEquals(expected, state.assignment.intValue(1))
                assertTrue(state.cost > 0L)
                assertEquals(before + predicted, state.cost)
                val committed = state.cost
                state.recompute()
                assertEquals(committed, state.cost)
            }
        }
    }

    @Test
    fun `independent literal product output constraints retain their violations`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="choice">0..2</var><var id="duration">0..3</var>
                </variables><constraints><intension>eq(duration,mul(eq(choice,0),3))</intension>
                <intension>ne(duration,3)</intension></constraints></instance>""",
        )
        val problem = parsed.problem
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, state.rootDomains[i].min)
        state.assignment.setInt(0, 1L)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        val predicted = state.netDelta(Move.IntSet(0, 0L))

        state.apply(Move.IntSet(0, 0L))

        assertEquals(3L, state.assignment.intValue(1))
        assertEquals(1L, predicted)
        assertEquals(1L, state.cost)
        state.recompute()
        assertEquals(1L, state.cost)
    }

    @Test
    fun `competing literal product definitions leave the output searched`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="choice">0..2</var><var id="duration">0..3</var>
                </variables><constraints><intension>eq(duration,mul(eq(choice,0),3))</intension>
                <intension>eq(mul(3,eq(choice,1)),duration)</intension></constraints></instance>""",
        )
        val problem = parsed.problem
        val network = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
            .network(problem.numIntVars, problem.numBoolVars)
        val products = problem.factors.filterIsInstance<Product>()

        assertFalse(network.isDefinedInt(1))
        assertEquals(products[0].b, products[1].a)
        assertEquals(
            setOf(listOf(2L, 0L)),
            BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                .map { it.ints.take(2) }.toSet(),
        )
    }

    @Test
    fun `other source multiplication shapes retain ordinary arithmetic lowering`() {
        for ((product, expected) in listOf(
            "mul(eq(choice,0),0)" to listOf(0L, 0L, 0L),
            "mul(-3,eq(choice,0))" to listOf(-3L, 0L, 0L),
            "mul(choice,3)" to listOf(0L, 3L, 6L),
            "mul(eq(choice,0),3,2)" to listOf(6L, 0L, 0L),
            "mul(eq(choice,0),add(choice,1))" to listOf(1L, 0L, 0L),
        )) {
            val parsed = Xcsp3.parse(
                """<instance><variables><var id="choice">0..2</var><var id="duration">-3..6</var>
                    </variables><constraints><intension>eq(duration,$product)</intension>
                    </constraints></instance>""",
            )
            val problem = parsed.problem

            assertFalse(problem.factors.any { it is Product && it.result == 1 })
            assertEquals(
                expected.mapIndexed { choice, duration -> listOf(choice.toLong(), duration) }.toSet(),
                BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                    .map { it.ints.take(2) }.toSet(),
            )
        }
    }

    @Test
    fun `out of range literal products decline without wrapping`() {
        assertFailsWith<UnsupportedXcsp3Exception> {
            Xcsp3.parse(
                """<instance><variables><var id="choice">0..1</var><var id="duration">0..3</var>
                    </variables><constraints><intension>eq(duration,mul(eq(choice,0),2147483648))</intension>
                    </constraints></instance>""",
            )
        }
    }

    @Test
    fun `min and max chains retain aliases and exact source solutions`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables>
                <var id="x">0..1</var><var id="y">0..1</var>
                <var id="r">0..2</var><var id="s">0..2</var>
                </variables><constraints>
                <intension>eq(r,$extreme(add(x,1),$extreme(y,1)))</intension>
                <intension>eq(s,r)</intension>
                </constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val state = LocalSearchState(problem.bake(), Random(0))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            assertTrue(state.invariants!!.isDefinedInt(2))
            assertTrue(state.invariants!!.isDefinedInt(3))
            val expected = (0L..1L).flatMap { x ->
                (0L..1L).map { y ->
                    val r = if (extreme == "min") minOf(x + 1, minOf(y, 1)) else maxOf(x + 1, maxOf(y, 1))
                    listOf(x, y, r, r)
                }
            }.toSet()
            val actual = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                .map { it.ints.take(4) }.toSet()
            assertEquals(expected, actual)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            for (x in 0L..1L) {
                for (y in 0L..1L) {
                    state.apply(Move.Compound(listOf(Move.IntSet(0, x), Move.IntSet(1, y))))
                    val r = if (extreme == "min") minOf(x + 1, minOf(y, 1)) else maxOf(x + 1, maxOf(y, 1))
                    assertEquals(r, state.assignment.intValue(2))
                    assertEquals(r, state.assignment.intValue(3))
                    assertEquals(0L, state.cost)
                    val cost = state.cost
                    state.recompute()
                    assertEquals(cost, state.cost)
                }
            }
        }
    }

    @Test
    fun `clipped min and max outputs remain violated and repair searched inputs`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">0..4</var><var id="y">0..4</var>
                <var id="r">1..3</var></variables><constraints>
                <intension>eq(r,$extreme(x,y))</intension>
                <intension>ne(r,x)</intension>
                </constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val fixed = if (extreme == "min") 3L else 1L
            val state = LocalSearchState(problem.bake(), Random(0), Assumptions(ints = mapOf(0 to fixed)))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            val initial = if (extreme == "min") 0L else 4L
            state.assignment.setInt(0, fixed)
            state.assignment.setInt(1, initial)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            assertEquals(if (extreme == "min") 1L else 3L, state.assignment.intValue(2))
            assertTrue(state.cost > 0)
            state.moveSink.addChannelingIntSet(state, 2, 2L)
            val repair = state.moveSink.list.single()
            state.apply(repair)
            assertEquals(2L, state.assignment.intValue(2))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `pinned outputs survive sweep and per move updates`() {
        val parsed = Xcsp3.parse(
            """
            <instance><variables><var id="x">0..3</var><var id="y">0..3</var>
            <var id="r">0..3</var></variables><constraints>
            <intension>eq(r,min(x,y))</intension></constraints></instance>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
        val pins = Assumptions(ints = mapOf(2 to 2L, 0 to 3L))
        val state = LocalSearchState(problem.bake(), Random(0), pins)
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        state.assignment.setInt(0, 3L)
        state.assignment.setInt(1, 0L)
        state.assignment.setInt(2, 2L)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, pins)
        state.recompute()
        assertEquals(2L, state.assignment.intValue(2))
        state.apply(Move.IntSet(1, 1L))
        assertEquals(2L, state.assignment.intValue(2))
        assertTrue(state.cost > 0L)
        state.moveSink.addChannelingIntSet(state, 2, 2L)
        state.apply(state.moveSink.list.single())
        assertEquals(3L, state.assignment.intValue(0))
        assertEquals(2L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `independent output constraints repair through maintained extrema`() {
        for (extreme in listOf("min", "max")) {
            val parsed = Xcsp3.parse(
                """
                <instance><variables><var id="x">0..4</var><var id="y">0..4</var>
                <var id="r">1..3</var></variables><constraints>
                <intension>eq(r,$extreme(x,y))</intension>
                <intension>ne(r,x)</intension></constraints></instance>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
            val fixed = if (extreme == "min") 3L else 1L
            val pins = Assumptions(ints = mapOf(0 to fixed))
            val state = LocalSearchState(problem.bake(), Random(0), pins)
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, fixed)
            state.assignment.setInt(1, fixed)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            assertTrue(state.cost > 0L)

            val factorId = problem.factors.lastIndex
            state.factors[factorId].proposeRepairMoves(state, factorId, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(fixed, state.assignment.intValue(0))
            assertEquals(2L, state.assignment.intValue(2))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }
}
