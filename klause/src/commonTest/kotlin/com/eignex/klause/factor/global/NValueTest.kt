package com.eignex.klause.factor.global

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class NValueTest {
    @Test
    fun `full distinct counts should retain native distinctness independently of value span`() {
        for (domain in listOf(
            IntDomain(0, 2),
            IntDomain(0, 2147483648),
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE),
            intDomainFromSurvivors(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)),
        )) {
            val factor = NValue(2, intArrayOf(0, 1))

            val reduced = assertIs<FactorReduction.Rewrite>(
                factor.structuralReduce(arrayOf(domain, domain, IntDomain(2, 2))),
            )

            assertContentEquals(intArrayOf(0, 1), assertIs<AllDifferent>(reduced.replacement.single()).vars)
        }
    }

    @Test
    fun `wide sparse full distinct reductions should preserve every solution`() {
        val values = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)
        val domain = intDomainFromSurvivors(values)
        val domains = arrayOf(domain, domain, IntDomain(2, 2))
        val factor = NValue(2, intArrayOf(0, 1))
        val reduced = assertIs<FactorReduction.Rewrite>(factor.structuralReduce(domains))
        val expected = values.flatMap { x -> values.filter { it != x }.map { y -> listOf(x, y, 2L) } }.toSet()

        for (factors in listOf(listOf(factor), reduced.replacement)) {
            val state = LocalSearchState(LocalSearchModel.open(Problem(0, 3, domains, factors.toTypedArray())), Random(3))
            val actual = values.flatMap { x ->
                values.mapNotNull { y ->
                    state.assignment.setInt(0, x)
                    state.assignment.setInt(1, y)
                    state.assignment.setInt(2, 2L)
                    state.recompute()
                    if (state.cost == 0L) listOf(x, y, 2L) else null
                }
            }.toSet()

            assertEquals(expected, actual)
        }
    }

    @Test
    fun `repeated positions should remain impossible in full distinct reductions`() {
        val domains = arrayOf(IntDomain(0, 1), IntDomain(2, 2))
        val reduced = assertIs<FactorReduction.Rewrite>(
            NValue(1, intArrayOf(0, 0)).structuralReduce(domains),
        )

        val actual = BruteForceSolver(Problem(0, 2, domains, reduced.replacement.toTypedArray()).bake())
            .enumerate(BruteForceParams(randomSeed = 0L)).toList()

        assertEquals(emptyList(), actual)
    }

    @Test
    fun `nonexact optional and excepted counts should retain their relation`() {
        val domains = arrayOf(IntDomain(0, 2147483648), IntDomain(0, 2147483648), IntDomain(2, 2))
        for (factor in listOf(
            NValue(2, intArrayOf(0, 1), mode = NValue.Mode.AtLeast),
            NValue(2, intArrayOf(0, 1), mode = NValue.Mode.AtMost),
            NValue(2, intArrayOf(0, 1), presents = intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            NValue(2, intArrayOf(0, 1), exceptSet = longArrayOf(0)),
        )) {
            assertEquals(FactorReduction.Unchanged, factor.structuralReduce(domains))
        }
    }

    @Test
    fun `nonfixed and other fixed targets should retain distinct counts`() {
        val domain = IntDomain(0, 2147483648)
        for (target in listOf(IntDomain(1, 3), IntDomain(0, 0), IntDomain(2, 2))) {
            val reduced = NValue(3, intArrayOf(0, 1, 2)).structuralReduce(arrayOf(domain, domain, domain, target))

            assertEquals(FactorReduction.Unchanged, reduced)
        }
    }

    @Test
    fun `one distinct value should retain equality reductions including singleton lists`() {
        val domain = IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)
        for (xs in listOf(intArrayOf(0), intArrayOf(0, 1))) {
            val reduced = assertIs<FactorReduction.Rewrite>(
                NValue(2, xs).structuralReduce(arrayOf(domain, domain, IntDomain(1, 1))),
            )

            assertEquals(xs.size - 1, reduced.replacement.size)
            for (row in reduced.replacement) assertEquals(LinearOp.EQ, assertIs<Linear>(row).op)
        }
    }

    @Test
    fun `remapping should preserve excluded values and paired presence roles`() {
        val factor = NValue(2, intArrayOf(0, 1, 0), presents = intArrayOf(
            Lit.make(0, true), Lit.make(1, false), Lit.make(0, true),
        ), exceptSet = longArrayOf(Long.MIN_VALUE, 0))

        val mapped = assertIs<NValue>(factor.remap(VarRemap(intArrayOf(1, 0), intArrayOf(2, 1, 0))))

        assertEquals(0, mapped.n)
        assertContentEquals(intArrayOf(2, 1, 2), mapped.xs)
        assertContentEquals(intArrayOf(Lit.make(1, true), Lit.make(0, false), Lit.make(1, true)), mapped.presents)
        assertContentEquals(longArrayOf(Long.MIN_VALUE, 0), mapped.exceptSet)
    }

    @Test
    fun `excluded value sets should participate in structural identity`() {
        val factor = NValue(2, intArrayOf(0, 1), exceptSet = longArrayOf(2, 0, 2))
        val reordered = NValue(2, intArrayOf(1, 0), exceptSet = longArrayOf(0, 2))
        val plain = NValue(2, intArrayOf(0, 1))

        assertEquals(factor.structuralKey(), reordered.structuralKey())
        assertNotEquals(factor.structuralKey(), plain.structuralKey())
    }

    @Test
    fun `one counted value should not reduce to equality when values are excluded`() {
        val factor = NValue(2, intArrayOf(0, 1), exceptSet = longArrayOf(0))

        val reduced = factor.structuralReduce(arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1)))

        assertEquals(FactorReduction.Unchanged, reduced)
    }
}
