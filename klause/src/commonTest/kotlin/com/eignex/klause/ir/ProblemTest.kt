package com.eignex.klause.ir

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.bake
import com.eignex.util.collections.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProblemTest {

    @Test
    fun `bake should fold root deductions while the raw problem stays unbaked`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        assertEquals(10, problem.finiteIntDomain(0).max)
        assertEquals(3, problem.bake().rootIntDomain(0).max)
    }

    @Test
    fun `problem should reject a factor with an invalid Boolean variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf(Clause(intArrayOf(Lit.make(1, true)))),
            )
        }

        assertTrue(error.message!!.contains("factor 0 references Boolean variable 1"))
    }

    @Test
    fun `problem should reject a factor with an invalid integer variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 1)),
                factors = arrayOf(Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 0)),
            )
        }

        assertTrue(error.message!!.contains("factor 0 references integer variable 1"))
    }

    @Test
    fun `model bounds should keep an open side behind the search clamp`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-8, 8)),
            factors = emptyArray(),
            openIntLo = booleanArrayOf(true),
        )

        assertFalse(problem.intBounds.hasLower(0))
        assertTrue(problem.intBounds.hasUpper(0))
        assertEquals(8, problem.intBounds.upper(0))
    }

    @Test
    fun `problem should keep open bounds without a search domain`() {
        val openUpper = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        assertEquals(null, problem.intDomainOrNull(0))
        assertFalse(problem.intBounds.hasUpper(0))
    }

    @Test
    fun `withFactors should keep a non-contiguous domain`() {
        val holey = IntDomain(0, 6).excludeValue(3)
        val spec = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(holey),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)),
        )

        val rewritten = spec.withFactors(emptyArray())

        assertEquals(holey, rewritten.intDomainOrNull(0))
        assertEquals(0, rewritten.numFactors)
    }

    @Test
    fun `source rewrites and baking should retain effective semantic policy`() {
        val semantics = SourceSemantics(
            SourceFormat.FLATZINC, undeclaredIntegers = UndeclaredIntegerBounds(-1_000_000, 1_000_000),
        ).resolve(SemanticOverrides(undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(3_000_000))))
        val source = Problem(
            0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), semantics = semantics,
        )

        val views = listOf(
            source.withSettings(ProblemSettings(largeSpanThreshold = 7)),
            source.withIntDomains(arrayOf(IntDomain(1, 2))),
            source.withFactors(emptyArray()),
            source.withDeclaredIntDomains(SourceIntDomains.ofBounds(source.intBounds)),
            source.bake(),
        )

        for (view in views) {
            assertEquals(semantics.declared, view.semantics.declared)
            assertEquals(semantics.effective, view.semantics.effective)
        }
    }

    @Test
    fun `of should keep its model when the caller later writes the arrays it passed`() {
        val noColumns = IntBounds.fromModelBounds(LongArray(0), LongArray(0), null, null)
        val overloads = listOf<Pair<String, (Array<Factor>, BooleanArray, DoubleArray, DoubleArray) -> Problem>>(
            "value sets" to { factors, implied, lower, upper ->
                Problem.of(1, 0, emptyArray(), factors, implied, numRealVars = 1, realLower = lower, realUpper = upper)
            },
            "declarations" to { factors, implied, lower, upper ->
                Problem.of(1, 0, SourceIntDomains.ofBounds(noColumns), factors, implied, numRealVars = 1,
                    realLower = lower, realUpper = upper)
            },
            "bounds" to { factors, implied, lower, upper ->
                Problem.of(1, noColumns, factors, implied, numRealVars = 1, realLower = lower, realUpper = upper)
            },
        )
        for ((name, build) in overloads) {
            val clause = Clause(intArrayOf(Lit.make(0, true)))
            val factors = arrayOf<Factor>(clause)
            val implied = booleanArrayOf(false)
            val lower = doubleArrayOf(0.0)
            val upper = doubleArrayOf(1.0)
            val problem = build(factors, implied, lower, upper)

            factors[0] = Clause(intArrayOf(Lit.make(0, false)))
            implied[0] = true
            lower[0] = -1.0
            upper[0] = 2.0

            assertSame(clause, problem.factors[0], name)
            assertEquals(listOf(false), problem.impliedFactorMask?.toList(), name)
            assertEquals(0.0, problem.realLower[0], name)
            assertEquals(1.0, problem.realUpper[0], name)
        }
    }

    @Test
    fun `of should keep its integer declarations when the caller later writes the arrays it passed`() {
        val domains = arrayOf(IntDomain(0, 3))
        val openLower = booleanArrayOf(true)
        val openUpper = booleanArrayOf(false)
        val problem = Problem.of(0, 1, domains, emptyList(), openIntLo = openLower, openIntHi = openUpper)

        domains[0] = IntDomain(1, 2)
        openLower[0] = false
        openUpper[0] = true

        assertEquals(3, problem.declaredIntDomains.finiteDomain(0).max)
        assertTrue(problem.intBounds.isOpenLower(0))
        assertFalse(problem.intBounds.isOpenUpper(0))
    }

    @Test
    fun `withFactors should keep its rows and mask when the caller later writes the ones it passed`() {
        val source = Problem.of(1, 0, emptyArray(), emptyList())
        val rows = mutableListOf<Factor>(Clause(intArrayOf(Lit.make(0, true))))
        val implied = booleanArrayOf(true)

        val rewritten = source.withFactors(rows, implied)
        rows.clear()
        implied[0] = false

        assertEquals(1, rewritten.numFactors)
        assertEquals(listOf(true), rewritten.impliedFactorMask?.toList())
    }
}
