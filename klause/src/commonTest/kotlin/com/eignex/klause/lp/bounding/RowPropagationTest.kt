package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowPropagationTest {
    @Test
    fun `selected contributions preserve coefficient direction and strictness`() {
        for (target in listOf(-2L, 2L)) {
            for (other in listOf(-3L, 3L)) {
                for (minimum in listOf(false, true)) {
                    for (strict in listOf(false, true)) {
                        val endpoint = ExactLpSide(ExactLpNumber.of(2L), strict)
                        val lower = minimum == (other > 0)
                        val bounds = if (lower) ExactLpBounds(lower = endpoint) else ExactLpBounds(upper = endpoint)
                        val state = row(listOf(f(target), f(other)), f(7), listOf(ExactLpBounds(), bounds))
                        val pass = run(state)
                        val result = pass.implications.single { it.column == 0 }
                        val expected = (f(7) - f(other) * f(2)) * f(target).reciprocal()
                        assertEquals(expected, result.side.number.value)
                        assertEquals(minimum == (target > 0), result.upper)
                        assertEquals(strict, result.side.strict)
                        assertTrue(missed(state, pass).isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `target endpoint does not supply strictness for its own implication`() {
        val state = row(listOf(f(1), f(1)), f(2), listOf(
            ExactLpBounds(lower = side(0, true)), ExactLpBounds(lower = side(1)),
        ))
        val result = run(state).implications.single { it.column == 0 && it.upper }
        assertEquals(f(1), result.side.number.value)
        assertEquals(false, result.side.strict)
    }

    @Test
    fun `zero coefficients neither need endpoints nor transfer strictness`() {
        val state = row(listOf(f(1), f(0)), f(2), listOf(
            ExactLpBounds(), ExactLpBounds(lower = side(0, true)),
        ))
        val result = run(state).implications.filter { it.column == 0 }
        assertEquals(2, result.size)
        assertTrue(result.all { it.side.number.value == f(2) && !it.side.strict })
    }

    @Test
    fun `only a single missing selected contribution permits that target`() {
        for (missing in 0..3) {
            val bounds = List(3) { if (it < missing) ExactLpBounds() else ExactLpBounds(lower = side(0)) }
            val pass = run(row(List(3) { f(1) }, f(4), bounds))
            assertEquals(when (missing) { 0 -> 3; 1 -> 1; else -> 0 },
                pass.implications.count { it.column < 3 && it.upper })
        }
    }

    @Test
    fun `strict equality surplus explains a crossed interval`() {
        val state = row(listOf(f(1), f(1)), f(0), listOf(
            ExactLpBounds(lower = side(0)), ExactLpBounds(lower = side(0, true)),
        ))
        assertNotNull(run(state).conflict)
    }

    @Test
    fun `rational rows retain intervals smaller than floating precision`() {
        val tiny = BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 70)
        val state = row(listOf(f(1)), tiny, listOf(ExactLpBounds()))
        val pass = run(state)
        assertEquals(tiny, pass.lower[0]?.side?.number?.value)
        assertEquals(tiny, pass.upper[0]?.side?.number?.value)
        assertNull(pass.conflict)
    }

    @Test
    fun `integer rounding handles both signs and strict endpoints`() {
        for (numerator in -5L..5L) {
            for (strict in listOf(false, true)) {
                for (upper in listOf(false, true)) {
                    val value = f(numerator) * f(2).reciprocal()
                    val budget = RowPropagationBudget(RowPropagationLimits())
                    val rounded = assertNotNull(budget.round(value, upper, strict))
                    val admissible = (-10L..10L).filter {
                        val comparison = f(it).compareTo(value)
                        if (upper) comparison < 0 || (!strict && comparison == 0)
                        else comparison > 0 || (!strict && comparison == 0)
                    }
                    assertEquals(f(if (upper) admissible.max() else admissible.min()), rounded)
                }
            }
        }
    }

    @Test
    fun `integer equality with odd right hand side produces a conflict`() {
        val state = row(listOf(f(2)), f(-3), listOf(ExactLpBounds()), integral = true)
        assertNotNull(run(state).conflict)
    }

    @Test
    fun `nonintegral origins do not authorize integer coordinate rounding`() {
        val source = row(listOf(f(2)), f(-3), listOf(ExactLpBounds()), integral = true)
        val shifted = source.baseModel.recentered(listOf(ExactLpNumber.of(f(1) * f(2).reciprocal())))
        val pass = run(LpExactState(shifted))
        assertNull(pass.conflict)
        assertEquals(f(-2), pass.lower[0]?.side?.number?.value)
    }

    @Test
    fun `unbounded logical columns are genuine selected contributions`() {
        val model = row(listOf(f(1)), f(2), listOf(ExactLpBounds())).baseModel
        val state = LpExactState(model.copy(columns = listOf(
            model.column(0), ExactLpColumn(ExactLpBounds(), integral = false),
        )))
        assertTrue(run(state).implications.isEmpty())
    }

    @Test
    fun `scan bit allocation and cancellation exhaustion decline without a result`() {
        val state = row(listOf(f(1)), f(2), listOf(ExactLpBounds()))
        for (limits in listOf(
            RowPropagationLimits(work = 0), RowPropagationLimits(allocation = 0),
        )) {
            val propagation = ExactRowPropagation(limits)
            assertNull(propagation.scan(state) { SearchAtomPremise.All(emptyList()) })
        }
        val propagation = ExactRowPropagation()
        propagation.budget.cancellation = Cancellation { true }
        assertNull(propagation.scan(state) { SearchAtomPremise.All(emptyList()) })
    }

    @Test
    fun `debug reference distinguishes policy row skips from lost implications`() {
        val state = row(listOf(f(1)), f(2), listOf(ExactLpBounds()))
        val propagation = ExactRowPropagation(RowPropagationLimits(rowLength = 1))
        val pass = assertNotNull(propagation.scan(state) { SearchAtomPremise.All(emptyList()) })
        assertEquals(1, propagation.rowSkips)
        assertEquals(2, missed(state, pass).size)
    }

    private fun run(state: LpExactState): ExactRowPropagation.Pass = assertNotNull(
        ExactRowPropagation().scan(state) { SearchAtomPremise.All(emptyList()) },
    )

    private fun row(
        coefficients: List<BigFraction>,
        rhs: BigFraction,
        bounds: List<ExactLpBounds>,
        integral: Boolean = false,
    ): LpExactState = LpExactState(ExactLpModel(
        coefficients.map { listOf(ExactLpEntry(0, ExactLpNumber.of(it))) },
        listOf(ExactLpNumber.of(rhs)),
        bounds.map { ExactLpColumn(it, integral = integral) } +
            ExactLpColumn(ExactLpBounds(side(0), side(0)), integral = false),
        listOf(ExactLpRow()),
        ExactLpObjective(List(bounds.size + 1) { ExactLpNumber.of(0L) }),
    ))

    private fun side(value: Long, strict: Boolean = false): ExactLpSide = ExactLpSide(ExactLpNumber.of(value), strict)
    private fun f(value: Long): BigFraction = BigFraction.ofLong(value)

    private fun missed(state: LpExactState, pass: ExactRowPropagation.Pass): List<Pair<Int, Boolean>> {
        val model = state.model
        val result = ArrayList<Pair<Int, Boolean>>()
        for (row in 0 until model.m) {
            val terms = (0 until model.n).mapNotNull { column ->
                model.entries(column).singleOrNull { it.row == row }?.number?.value
                    ?.takeUnless { it.isZero }?.let { column to it }
            } + (model.n + row to BigFraction.ONE)
            for ((target, coefficient) in terms) {
                for (upper in listOf(false, true)) {
                    val others = terms.filter { it.first != target }
                    val selected = others.map { (column, value) ->
                        val lower = upper == (coefficient.signum() == value.signum())
                        (if (lower) pass.lower[column] else pass.upper[column])?.let { value to it.side }
                    }
                    if (selected.any { it == null }) continue
                    val sides = selected.filterNotNull()
                    val sum = sides.fold(BigFraction.ZERO) { total, (a, b) -> total + a * b.number.value }
                    val bound = (model.rhs(row).value - sum) * coefficient.reciprocal()
                    val current = if (upper) pass.upper[target] else pass.lower[target]
                    val comparison = current?.side?.number?.value?.compareTo(bound)
                    if (comparison == null || (if (upper) comparison > 0 else comparison < 0) ||
                        (comparison == 0 && sides.any { it.second.strict } && !current.side.strict)
                    ) result.add(target to upper)
                }
            }
        }
        return result
    }
}
