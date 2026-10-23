package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchExecutionAccounting
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearSourcePointCandidateTest {
    @Test
    fun `projected source clauses should imply signed comparisons across retraction`() {
        for (mask in 0..3) {
            val first = Lit.make(0, mask and 1 == 0)
            val second = Lit.make(1, mask and 2 == 0)
            val gate = Lit.make(2, true)
            val low = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 0)
            val high = ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 1)
            val model = Problem(3, 0, emptyArray(), arrayOf(low, high,
                Clause(intArrayOf(Lit.negate(first), gate)),
                Clause(intArrayOf(Lit.negate(gate), Lit.negate(second))),
                Cardinality.exactlyOne(intArrayOf(first, second))), numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
            val owner = SearchSession(emptyList())
            owner.initialize()
            val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
                FactorProjectionProviders.Default.prepare(model), Cancellation.Never))
            val point = listOf(first, second).map {
                if (Lit.isPositive(it)) BigFraction.MINUS_ONE else BigFraction.ONE
            }

            assertNull(checker.check(point, owner))
            assertEquals(ComponentResult.Consistent, owner.push(SearchDecision.Bool(first)))

            assertEquals(!Lit.isPositive(second), owner.boolValue(1))
            assertNull(owner.boolValue(2))
            owner.popTo(0)
            assertEquals(ComponentResult.Consistent, owner.push(SearchDecision.Bool(second)))
            assertEquals(!Lit.isPositive(first), owner.boolValue(0))
        }
    }

    @Test
    fun `projected source clauses should preserve every Boolean source model`() {
        val low = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 0)
        val high = ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 1)
        val model = Problem(3, 0, emptyArray(), arrayOf(low, high,
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(1, false))),
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))), numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        var preserved = 0
        for (mask in 0..7) {
            val first = mask and 1 != 0
            val second = mask and 2 != 0
            val gate = mask and 4 != 0
            if ((first && !gate) || (gate && second) || first == second) continue
            val owner = SearchSession(emptyList())
            owner.initialize()
            owner.publish(Lit.make(2, gate))
            val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
                FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

            assertNull(checker.check(listOf(BigFraction.ZERO, BigFraction.ZERO), owner))

            assertEquals(ComponentResult.Consistent, owner.push(SearchDecision.Bool(Lit.make(0, first))))
            assertEquals(ComponentResult.Consistent, owner.push(SearchDecision.Bool(Lit.make(1, second))))
            assertEquals(gate, owner.boolValue(2))
            preserved++
        }
        assertEquals(2, preserved)
    }

    @Test
    fun `cancelled source clause derivations should publish no learned clause`() {
        val low = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 0)
        val high = ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 1)
        val model = Problem(3, 0, emptyArray(), arrayOf(low, high,
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(1, false))),
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))), numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        var cancelled = false
        val parent = Cancellation { cancelled }
        val owner = SearchSession(emptyList(), executionControls = SearchExecutionControlAdapter(parent))
        owner.initialize()
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never) {
            if (it == "SOURCE_POINT_CUBE_ATTEMPT") cancelled = true
        })

        val result = checker.check(listOf(BigFraction.ZERO, BigFraction.ZERO), owner)

        assertNull(result)
        assertTrue(cancelled)
        assertEquals(0, owner.learnedClauseCount)
        for (id in 0..2) assertNull(owner.boolValue(id))
    }

    @Test
    fun `compiled cardinalities and real extrema admit a checked source point`() {
        val parsed = SmtLib.parse(
            """
            (set-logic QF_LRA)
            (declare-const p Bool) (declare-const q Bool)
            (declare-const x Real) (declare-const y Real)
            (assert (= (+ (ite p 1 0) (ite q 1 0)) 1))
            (assert (= p (<= x y)))
            (assert (= (+ x (* 2.0 y)) 3.0))
            (assert (>= (ite (<= x y) x y) 1.0))
            """.trimIndent(),
        )
        val engine = OpenTheoryEngine(parsed.model, ProblemPipeline.EXACT_LRA, PresolveConfig.NONE,
            false, Cancellation.Never, null)

        val result = assertIs<OpenTheoryResult.Sat>(engine.solve())

        assertEquals(0, parsed.model.numIntVars)
        assertEquals(3, parsed.model.numRealVars)
        assertEquals(1, parsed.model.factors.filterIsInstance<Cardinality>().size)
        assertEquals("1", result.assignment.realValue(parsed.realVarNames.getValue("x")))
        assertEquals("1", result.assignment.realValue(parsed.realVarNames.getValue("y")))
        assertEquals(true, result.assignment.boolValue(parsed.boolVarNames.getValue("p")))
        assertEquals(false, result.assignment.boolValue(parsed.boolVarNames.getValue("q")))
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("SOURCE_POINT_ACCEPTED"))
        assertEquals(0L, result.stats.openTheory.openBoolDecisions)
    }

    @Test
    fun `open traversal publishes a globally checked point before completing its Boolean tree`() {
        val refs = intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1))
        val coefficients = listOf(BigFraction.ONE, BigFraction.ofLong(2))
        val equation = ExactLinear.of(refs, coefficients, LinearOp.EQ, BigFraction.ZERO)
        val atom = ExactLinear.of(refs, coefficients, LinearOp.EQ, BigFraction.ZERO, activator = 0)
        val model = Problem(2, 0, emptyArray(), arrayOf(equation, atom), numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val engine = OpenTheoryEngine(model, ProblemPipeline.EXACT_LRA, PresolveConfig.NONE,
            false, Cancellation.Never, null)

        val result = assertIs<OpenTheoryResult.Sat>(engine.solve())

        assertEquals(true, result.assignment.boolValue(0))
        assertEquals(false, result.assignment.boolValue(1))
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("SOURCE_POINT_ACCEPTED"))
        assertEquals(0L, result.stats.openTheory.openBoolDecisions)
    }

    @Test
    fun `an explicit decision limit retains ordinary traversal semantics`() {
        val refs = intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1))
        val coefficients = listOf(BigFraction.ONE, BigFraction.ofLong(2))
        val equation = ExactLinear.of(refs, coefficients, LinearOp.EQ, BigFraction.ZERO)
        val atom = ExactLinear.of(refs, coefficients, LinearOp.EQ, BigFraction.ZERO, activator = 0)
        val model = Problem(2, 0, emptyArray(), arrayOf(equation, atom), numRealVars = 2,
            realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val engine = OpenTheoryEngine(model, ProblemPipeline.EXACT_LRA, PresolveConfig.NONE,
            false, Cancellation.Never, null)

        val result = assertIs<OpenTheoryResult.Unknown>(engine.solve(TheoryParams(maxDecisions = 0)))

        assertEquals(TerminationReason.BudgetExhausted, result.reason)
        assertNull(result.stats.smt.completeSearchStages?.get("SOURCE_POINT_ADMITTED"))
    }

    @Test
    fun `a checked source point can differ from a transient branch without changing its trail`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 0)
        val model = Problem(1, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val owner = SearchSession(emptyList())
        owner.initialize()
        owner.push(SearchDecision.Bool(Lit.make(0, true)))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = assertNotNull(checker.check(listOf(BigFraction.ONE), owner))

        assertEquals(false, result.valueOf(SearchBoolValue(0)))
        assertEquals(BigFraction.ONE, result.valueOf(SearchExactRealValue(SourceRealId(0))))
        assertEquals(true, owner.boolValue(0))
        assertEquals(1, owner.decisionLevel)
    }

    @Test
    fun `a containing root pin cannot be replaced by a candidate comparison truth`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 0)
        val model = Problem(1, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val owner = SearchSession(emptyList())
        owner.initialize()
        owner.publish(Lit.make(0, true))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ONE), owner)

        assertNull(result)
        assertEquals(true, owner.boolValue(0))
        assertEquals(0, owner.decisionLevel)
    }

    @Test
    fun `a strict boundary cannot satisfy its required source atom`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, strict = true, activator = 0)
        val model = Problem(1, 0, emptyArray(), arrayOf(row, Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ZERO), SearchSession(emptyList()))

        assertNull(result)
    }

    @Test
    fun `a disequality retains its exact source truth`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.NE, BigFraction.ZERO, activator = 0)
        val model = Problem(1, 0, emptyArray(), arrayOf(row, Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = assertNotNull(checker.check(listOf(BigFraction.ONE), SearchSession(emptyList())))

        assertEquals(true, result.valueOf(SearchBoolValue(0)))
    }

    @Test
    fun `inconsistent rows sharing an activator reject the proposed point`() {
        val low = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 0)
        val high = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ONE, activator = 0)
        val model = Problem(1, 0, emptyArray(), arrayOf(low, high), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ONE), SearchSession(emptyList()))

        assertNull(result)
    }

    @Test
    fun `source gates complete across repeated scans in reverse dependency order`() {
        val atom = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.EQ, BigFraction.ZERO, activator = 0)
        val model = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))), atom,
        ), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = assertNotNull(checker.check(listOf(BigFraction.ZERO), SearchSession(emptyList())))

        assertEquals(true, result.valueOf(SearchBoolValue(2)))
    }

    @Test
    fun `a source cardinality must accept the same complete point`() {
        val low = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, activator = 0)
        val high = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ZERO, activator = 1)
        val model = Problem(2, 0, emptyArray(), arrayOf(low, high,
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val stages = mutableListOf<String>()
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never) { stages += it })

        val result = checker.check(listOf(BigFraction.ZERO), SearchSession(emptyList()))

        assertNull(result)
        assertTrue("SOURCE_POINT_SHARED_CHECK" in stages)
        assertTrue("SOURCE_POINT_ACCEPTED" !in stages)
    }

    @Test
    fun `declared real bounds remain authoritative in the isolated source check`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val stages = mutableListOf<String>()
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never) { stages += it })

        val result = checker.check(listOf(BigFraction.ofLong(2)), SearchSession(emptyList()))

        assertNull(result)
        assertTrue("SOURCE_POINT_SHARED_CHECK" in stages)
    }

    @Test
    fun `an uncovered Boolean receives a private value checked with the full source`() {
        val model = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val owner = SearchSession(emptyList())
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = assertNotNull(checker.check(listOf(BigFraction.ZERO), owner))

        assertEquals(false, result.valueOf(SearchBoolValue(1)))
        assertNull(owner.boolValue(0))
        assertNull(owner.boolValue(1))
    }

    @Test
    fun `a rejected Boolean completion does not prune the containing search`() {
        val model = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        ), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val owner = SearchSession(emptyList())
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ZERO), owner)

        assertNull(result)
        assertEquals(ComponentResult.Consistent, owner.push(SearchDecision.Bool(Lit.make(0, true))))
        assertNull(owner.executionControls.stopCause)
    }

    @Test
    fun `rational coordinates retain their exact value through publication`() {
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))
        val atom = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.EQ, third, activator = 0)
        val model = Problem(1, 0, emptyArray(), arrayOf(atom), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))
        val point = mutableListOf(third)

        val result = assertNotNull(checker.check(point, SearchSession(emptyList())))
        point[0] = BigFraction.ZERO

        assertEquals(third, result.valueOf(SearchExactRealValue(SourceRealId(0))))
        assertEquals(true, result.valueOf(SearchBoolValue(0)))
    }

    @Test
    fun `an oversized exact point declines before a complete source check`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val stages = mutableListOf<String>()
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never) { stages += it })

        val result = checker.check(listOf(BigFraction.of(BIG_ONE shl 4096, BIG_ONE)),
            SearchSession(emptyList()))

        assertNull(result)
        assertTrue("SOURCE_POINT_SHARED_CHECK" !in stages)
    }

    @Test
    fun `a missing coordinate cannot become a checked witness`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(emptyList(), SearchSession(emptyList()))

        assertNull(result)
    }

    @Test
    fun `a refused containing check cannot enter private witness validation`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val owner = SearchSession(emptyList(), maxChecks = 0)
        val stages = mutableListOf<String>()
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never) { stages += it })

        val result = checker.check(listOf(BigFraction.ZERO), owner)

        assertNull(result)
        assertTrue("SOURCE_POINT_BOOLEAN_COMPLETE" in stages)
        assertTrue("SOURCE_POINT_SHARED_CHECK" !in stages)
    }

    @Test
    fun `an exhausted native allowance prevents candidate entry`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        var charged = 0L
        var checks = 0
        val parent = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun charge(units: Long) { charged += units }
        }
        val accounting = object : SearchExecutionAccounting {
            override val exhausted: Boolean get() = true
            override fun consumeCheck(): Boolean {
                checks++
                return false
            }
        }
        val owner = SearchSession(emptyList(), executionControls = SearchExecutionControlAdapter(parent, accounting))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ZERO), owner)

        assertNull(result)
        assertEquals(0L, charged)
        assertEquals(0, checks)
    }

    @Test
    fun `operation cancellation withholds the candidate while retaining caller charges`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        var charged = 0L
        val parent = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun charge(units: Long) { charged += units }
        }
        val controls = SearchExecutionControlAdapter(parent, allowance = { Cancellation { charged > 2 } })
        val owner = SearchSession(emptyList(), executionControls = controls)
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ZERO), owner)

        assertNull(result)
        assertTrue(charged > 2)
        assertNull(owner.boolValue(0))
    }

    @Test
    fun `all candidate work forwards to an overridden caller charge method`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        var charged = 0L
        val parent = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun charge(units: Long) { charged += units }
            override fun workMeter(): WorkMeter = WorkMeter { error("charge override was bypassed") }
        }
        val owner = SearchSession(emptyList(), executionControls = SearchExecutionControlAdapter(parent))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))

        val result = checker.check(listOf(BigFraction.ZERO), owner)

        assertNotNull(result)
        assertTrue(charged > 10)
    }

    @Test
    fun `the same borrowed point is not checked twice`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val checker = assertNotNull(LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never))
        val point = listOf(BigFraction.ZERO)

        assertNotNull(checker.check(point, SearchSession(emptyList())))
        val repeated = checker.check(point, SearchSession(emptyList()))

        assertNull(repeated)
    }

    @Test
    fun `candidate coordinate storage declines above its private limit`() {
        val model = Problem(65_536, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val checker = LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(model), Cancellation.Never)

        assertNull(checker)
    }

    @Test
    fun `a different prepared source cannot lend its projection authority`() {
        val model = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val other = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val checker = LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders.Default.prepare(other), Cancellation.Never)

        assertNull(checker)
    }

    @Test
    fun `an independently selected provider set declines candidate completion`() {
        val model = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val checker = LinearSourcePointCandidate.prepare(model,
            FactorProjectionProviders().prepare(model), Cancellation.Never)

        assertNull(checker)
    }
}
