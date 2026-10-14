package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductInvariantTest {
    @Test
    fun `predicted alias moves agree with whole factor grading`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val problem = Problem(0, 2, Array(2) { IntDomain(-10, 10) },
                arrayOf<Factor>(Product(a, b, result)))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for (other in listOf(0L, 1L, -1L, 4L)) {
                for ((old, target) in listOf(2L to 3L, -2L to -3L, 0L to 1L, 1L to 0L)) {
                    state.assignment.setInt(0, old)
                    state.assignment.setInt(1, other)
                    state.recompute()
                    val before = state.cost
                    val predicted = state.netDelta(Move.IntSet(0, target))
                    state.assignment.setInt(0, target)
                    state.recompute()

                    assertEquals(state.cost - before, predicted, "$roles $other $old -> $target")
                }
            }
        }
    }

    @Test
    fun `committed alias moves agree with whole factor grading`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val problem = Problem(0, 2, Array(2) { IntDomain(-10, 10) },
                arrayOf<Factor>(Product(a, b, result)))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for (other in listOf(0L, 1L, -1L, 4L)) {
                for ((old, target) in listOf(2L to 3L, -2L to -3L, 0L to 1L, 1L to 0L)) {
                    state.assignment.setInt(0, old)
                    state.assignment.setInt(1, other)
                    state.recompute()

                    val before = state.cost
                    state.assignment.setInt(0, target)
                    val delta = state.factors[0].applyIntSet(state, 0, 0, old)
                    state.recompute()
                    assertEquals(state.cost - before, delta.toLong(), "$roles $other $old -> $target")
                    state.assignment.setInt(0, old)
                    state.recompute()
                    state.apply(Move.IntSet(0, target))
                    val committed = state.cost
                    state.recompute()

                    assertEquals(state.cost, committed, "$roles $other $old -> $target")
                }
            }
        }
    }

    @Test
    fun `alias repair candidates do not increase the whole residual`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            for (other in listOf(3L, 5L, 8L)) {
                val (a, b, result) = roles
                val problem = Problem(0, 2, Array(2) { IntDomain(-10, 10).excludeValue(7) },
                    arrayOf<Factor>(Product(a, b, result)))
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
                state.assignment.setInt(0, 2)
                state.assignment.setInt(1, other)
                state.recompute()
                val before = state.cost

                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                val moves = state.moveSink.list

                assertTrue(moves.isNotEmpty())
                for (move in moves) {
                    val candidate = move as Move.IntSet
                    assertTrue(candidate.newValue in state.rootDomains[candidate.varId])
                    val predicted = state.netDelta(move)
                    state.assignment.setInt(candidate.varId, candidate.newValue)
                    state.recompute()
                    val full = state.cost
                    assertTrue(full <= before, "$roles $other $move")
                    assertEquals(full - before, predicted)
                    state.assignment.setInt(0, 2)
                    state.assignment.setInt(1, other)
                    state.recompute()
                    state.apply(move)
                    assertEquals(full, state.cost)
                    state.assignment.setInt(0, 2)
                    state.assignment.setInt(1, other)
                    state.recompute()
                }
            }
        }
    }
    @Test
    fun `wide alias moves retain saturated full grading`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            for ((old, target) in listOf(
                3037000499L to 3037000500L, Long.MAX_VALUE - 1 to Long.MAX_VALUE,
                Long.MIN_VALUE to Long.MIN_VALUE + 1, -1L to 0L,
            )) {
                val (a, b, result) = roles
                val problem = Problem(0, 2,
                    arrayOf(IntDomain(minOf(old, target), maxOf(old, target)), IntDomain(-1, -1)),
                    arrayOf<Factor>(Product(a, b, result)))
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
                state.assignment.setInt(0, old)
                state.assignment.setInt(1, -1)
                state.recompute()
                val before = state.cost
                val predicted = state.netDelta(Move.IntSet(0, target))
                state.assignment.setInt(0, target)
                val committed = state.factors[0].applyIntSet(state, 0, 0, old)
                state.recompute()

                assertEquals(state.cost - before, predicted, "$roles $old -> $target")
                assertEquals(predicted, committed.toLong(), "$roles $old -> $target")
            }
        }
    }

    @Test
    fun `alias repairs respect frozen coordinates and domain holes`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(1, 4).excludeValue(3), IntDomain(8, 8)),
            arrayOf<Factor>(Product(0, 0, 1)))
        for (frozen in listOf(false, true)) {
            val assumptions = if (frozen) Assumptions.None.withInt(0, 2L) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1), assumptions)
            state.assignment.setInt(0, 2)
            state.assignment.setInt(1, 8)
            state.recompute()

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty())
        }
    }

    @Test
    fun `selected scoring projections preserve aliases introduced by remapping`() {
        val original = Product(2, 3, 4)
        val source = Problem(0, 5, Array(5) { IntDomain(-10, 10) }, arrayOf<Factor>(original))
        val selected = FactorProjectionProviders(scoring = ScoringProjectionProvider { factor ->
            if (factor !== original) null else BuiltInScoringProjectionProvider.select(factor)
        }).prepare(source)
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val mapped = original.remap(VarRemap(intArrayOf(), intArrayOf(-1, -1, a, b, result)))
            val view = Problem(0, 2, Array(2) { IntDomain(-10, 10) }, arrayOf(mapped))
            val state = LocalSearchState(LocalSearchModel.open(view), Random(1),
                projection = LocalSearchProblem(view, selected.projected(view, intArrayOf(0))))
            state.assignment.setInt(0, 2)
            state.assignment.setInt(1, 4)
            state.recompute()
            val before = state.cost
            val move = Move.IntSet(0, 3)
            val predicted = state.netDelta(move)

            state.apply(move)
            val committed = state.cost
            state.recompute()

            val values = longArrayOf(3, 4)
            val sourceResidual = kotlin.math.abs(values[a] * values[b] - values[result])
            val full = compressViolation(sourceResidual, state.violationSoftCap).toLong()
            assertEquals(full - before, predicted, "$roles")
            assertEquals(full, committed, "$roles")
            assertEquals(full, state.cost, "$roles")
            assertEquals(2, original.a)
            assertEquals(3, original.b)
            assertEquals(4, original.result)
        }
    }

    @Test
    fun `overflowed products cannot satisfy a saturated result endpoint`() {
        for ((product, values) in listOf(
            Product(0, 1, 0) to longArrayOf(Long.MAX_VALUE, 2, 0),
            Product(0, 1, 0) to longArrayOf(Long.MIN_VALUE, 2, 0),
            Product(0, 0, 0) to longArrayOf(Long.MAX_VALUE, 0, 0),
            Product(0, 0, 1) to longArrayOf(3037000500, Long.MAX_VALUE, 0),
            Product(0, 1, 2) to longArrayOf(Long.MIN_VALUE, -1, Long.MAX_VALUE),
        )) {
            val problem = Problem(0, 3, Array(3) { IntDomain(values[it], values[it]) }, arrayOf<Factor>(product))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for (id in values.indices) state.assignment.setInt(id, values[id])
            val raw = (bigIntOf(values[product.a]) * bigIntOf(values[product.b]) -
                bigIntOf(values[product.result])).abs()
            val capped = if (raw > bigIntOf(Long.MAX_VALUE)) Long.MAX_VALUE else raw.toLongExact()

            state.recompute()

            assertTrue(state.factors[0].isViolated(state, 0))
            assertEquals(compressViolation(capped, state.violationSoftCap).toLong(), state.cost)
        }
    }

}
