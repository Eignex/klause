package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.RationalInterval
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PolynomialRootMergeTest {
    @Test
    fun `close irrational boundaries retain their original scalars and bounds`() {
        val interval = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))
        val roots = listOf(-200L, -201L, -202L).map { AlgebraicReal(RationalPolynomial.of(it, 0, 100), 2, interval) }
        val merge = PolynomialRootMerge(roots.map { listOf(it) }, AlgebraicArithmetic())

        val boundaries = generateSequence { merge.next() }.toList()

        assertEquals(roots.size, boundaries.size)
        for (index in roots.indices) {
            assertSame(roots[index], boundaries[index])
            assertEquals(interval, boundaries[index].interval)
        }
    }

    @Test
    fun `rational equality discovered by ordering preserves the original nonpoint boundary`() {
        val algebraic = AlgebraicArithmetic()
        val interval = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))
        val root = AlgebraicReal(RationalPolynomial.of(-9, 0, 4), 2, interval)
        val equal = algebraic.rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal())
        val upper = algebraic.rational(BigFraction.ofLong(2))
        val merge = PolynomialRootMerge(listOf(listOf(root), listOf(equal), listOf(upper)), algebraic)

        val first = merge.next()

        assertSame(root, first)
        assertNull(root.rational)
        assertEquals(interval, root.interval)
        assertSame(upper, merge.next())
        assertNull(merge.next())
    }

    @Test
    fun `completed comparisons fund the next boundary within remaining caller work`() {
        val interval = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))
        val roots = listOf(-201L, -200L, -202L).map { AlgebraicReal(RationalPolynomial.of(it, 0, 100), 2, interval) }
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        val merge = PolynomialRootMerge(roots.map { listOf(it) }, algebraic)
        assertSame(roots[1], merge.next())
        limit = spent + 64

        val next = merge.next()

        assertSame(roots[0], next)
        assertEquals(interval, roots[0].interval)
    }

    @Test
    fun `cancellation after an irrational boundary leaves a fresh merge independent`() {
        val interval = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))
        val roots = listOf(-200L, -201L, -202L).map { AlgebraicReal(RationalPolynomial.of(it, 0, 100), 2, interval) }
        var stopped = false
        val counted = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { stopped }))
        val merge = PolynomialRootMerge(roots.map { listOf(it) }, counted)
        assertSame(roots[0], merge.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { merge.next() }

        val fresh = PolynomialRootMerge(roots.map { listOf(it) }, AlgebraicArithmetic())
        for (root in roots) {
            assertSame(root, fresh.next())
            assertEquals(interval, root.interval)
        }
        assertNull(fresh.next())
    }

    @Test
    fun `empty root families retain an exhausted boundary sequence`() {
        for (families in listOf<List<List<AlgebraicReal>>>(emptyList(), listOf(emptyList()))) {
            val merge = PolynomialRootMerge(families, AlgebraicArithmetic())

            assertNull(merge.next())
            assertNull(merge.next())
        }
    }

    @Test
    fun `interleaved roots retain increasing order and the first equal scalar`() {
        val algebraic = AlgebraicArithmetic()
        val first = listOf(-3L, 2L).map { algebraic.rational(BigFraction.ofLong(it)) }
        val second = listOf(-1L, 4L).map { algebraic.rational(BigFraction.ofLong(it)) }
        val third = listOf(-1L, 0L, 3L).map { algebraic.rational(BigFraction.ofLong(it)) }
        val merge = PolynomialRootMerge(listOf(first, emptyList(), second, third), algebraic)

        val roots = generateSequence { merge.next() }.toList()

        assertEquals(listOf(-3L, -1L, 0L, 2L, 3L, 4L).map { BigFraction.ofLong(it) }, roots.map { it.rational })
        assertSame(second[0], roots[1])
    }

    @Test
    fun `equal irrational roots retain the first defining polynomial and bounds`() {
        val algebraic = AlgebraicArithmetic()
        val first = algebraic.roots(RationalPolynomial.of(-4, 0, 0, 0, 1))
        val second = algebraic.roots(RationalPolynomial.of(-2, 0, 1))
        val zero = algebraic.rational(BigFraction.ZERO)
        val merge = PolynomialRootMerge(listOf(first, listOf(zero), second), algebraic)

        val roots = generateSequence { merge.next() }.toList()

        assertEquals(3, roots.size)
        assertSame(first[0], roots[0])
        assertSame(zero, roots[1])
        assertSame(first[1], roots[2])
    }

    @Test
    fun `mutating source buffers leaves the captured root sequence independent`() {
        val algebraic = AlgebraicArithmetic()
        val lower = algebraic.rational(BigFraction.MINUS_ONE)
        val upper = algebraic.rational(BigFraction.ONE)
        val roots = mutableListOf(lower, upper)
        val families = mutableListOf(roots)
        val merge = PolynomialRootMerge(families, algebraic)

        roots.clear()
        families.clear()

        assertSame(lower, merge.next())
        assertSame(upper, merge.next())
        assertNull(merge.next())
    }

    @Test
    fun `an early boundary fits caller work before later comparisons exhaust it`() {
        val algebraic = AlgebraicArithmetic()
        val lower = algebraic.rational(BigFraction.ofLong(-2))
        val middle = algebraic.rational(BigFraction.MINUS_ONE)
        val leftTail = algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val rightTail = algebraic.roots(RationalPolynomial.of(-3, 0, 0, 0, 1)).last()
        var spent = 0L
        var limit: Long? = null
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = limit?.let { spent >= it } == true
            override fun workMeter(): WorkMeter = meter
        }
        val counted = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        val merge = PolynomialRootMerge(listOf(listOf(lower, leftTail), listOf(middle, rightTail)), counted)
        limit = spent + 32

        val first = merge.next()

        assertSame(lower, first)
        assertFailsWith<PolynomialInterrupted> { while (merge.next() != null) Unit }
    }

    @Test
    fun `containing cancellation leaves captured source roots independent for a fresh merge`() {
        val algebraic = AlgebraicArithmetic()
        val roots = listOf(algebraic.rational(BigFraction.ZERO), algebraic.rational(BigFraction.ONE))
        var stopped = false
        val counted = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { stopped }))
        val merge = PolynomialRootMerge(listOf(roots), counted)
        stopped = true

        assertFailsWith<PolynomialInterrupted> { merge.next() }

        val fresh = PolynomialRootMerge(listOf(roots), algebraic)
        assertSame(roots[0], fresh.next())
        assertSame(roots[1], fresh.next())
        assertNull(fresh.next())
    }
}
