package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefinitionalSweepTest {
    @Test
    fun `nested Boolean costs should decline a complete flat cost sweep`() {
        val problem = Problem(3, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, true)), true),
            BoolFoldDefinition(2, intArrayOf(Lit.make(1, true)), false),
        )))

        val objective = assertIs<FunctionalObjective>(sweep.functionalObjective(
            LinearObjective(longArrayOf(0, 0, 7)), Cancellation.Never, captureBooleanCosts = true,
        ))

        assertNull(objective.booleanCostSweep)
    }

    @Test
    fun `a bare Boolean cost should retain the ordinary definition view`() {
        val problem = Problem(3, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(2, intArrayOf(Lit.make(0, true), Lit.make(1, false)), true),
        )))

        val objective = assertIs<FunctionalObjective>(sweep.functionalObjective(
            LinearObjective(longArrayOf(3, 0, 7)), Cancellation.Never, captureBooleanCosts = true,
        ))

        assertNull(objective.booleanCostSweep)
    }

    @Test
    fun `an unpriced definition should prevent complete Boolean cost maintenance`() {
        val problem = Problem(4, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(2, intArrayOf(Lit.make(0, true)), true),
            BoolFoldDefinition(3, intArrayOf(Lit.make(1, true)), false),
        )))

        val objective = assertIs<FunctionalObjective>(sweep.functionalObjective(
            LinearObjective(longArrayOf(0, 0, 7)), Cancellation.Never, captureBooleanCosts = true,
        ))

        assertNull(objective.booleanCostSweep)
    }

    @Test
    fun `element objective gradients should follow the selected index`() {
        for (variableArray in listOf(false, true)) {
            val domains = arrayOf(IntDomain(0, 1), IntDomain(10, 20), IntDomain(10, 20), IntDomain(10, 20))
            val source = Problem(0, 4, domains, listOf(Element(0, 3,
                if (variableArray) longArrayOf(1, 2) else longArrayOf(10, 20), variableArray, indexOffset = 0)))
            val sweep = assertNotNull(DefinitionalSweep.infer(source, intArrayOf(3)))
            val objective = assertNotNull(sweep.functionalObjective(
                LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))))
            val assignment = com.eignex.klause.solver.Assignment(0, 4)
            assignment.setInt(0, 1)
            assignment.setInt(1, 10)
            assignment.setInt(2, 20)
            assignment.setInt(3, 20)

            val delta = objective.deltaIfApplied(assignment, Move.IntSet(0, 0))

            assertEquals(-10.0, delta, variableArray.toString())
        }
    }

    @Test
    fun `element objective gradients should retain the result for an invalid index`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(10, 20)),
            listOf(Element(0, 1, longArrayOf(10, 20), arrIsVars = false, indexOffset = 0)))
        val sweep = assertNotNull(DefinitionalSweep.infer(source, intArrayOf(1)))
        val objective = assertNotNull(sweep.functionalObjective(LinearObjective(intCoefficients = longArrayOf(0, 1))))
        val assignment = com.eignex.klause.solver.Assignment(0, 2)
        assignment.setInt(0, 1)
        assignment.setInt(1, 20)

        val delta = objective.deltaIfApplied(assignment, Move.IntSet(0, 2))

        assertEquals(0.0, delta)
    }

    @Test
    fun `element objective gradients should read only the selected variable cell`() {
        val source = Problem(0, 4,
            arrayOf(IntDomain(0, 1), IntDomain(10, 20), IntDomain(10, 20), IntDomain(10, 20)),
            listOf(Element(0, 3, longArrayOf(1, 2), arrIsVars = true, indexOffset = 0)))
        val sweep = assertNotNull(DefinitionalSweep.infer(source, intArrayOf(3)))
        val objective = assertNotNull(sweep.functionalObjective(
            LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))))
        val assignment = com.eignex.klause.solver.Assignment(0, 4)
        assignment.setInt(0, 1)
        assignment.setInt(1, 10)
        assignment.setInt(2, 20)
        assignment.setInt(3, 20)

        for ((variable, expected) in listOf(1 to 0.0, 2 to -5.0)) {
            assertEquals(expected, objective.deltaIfApplied(assignment, Move.IntSet(variable, 15)))
        }
    }

    @Test
    fun `definition inference should not spend work after its containing allowance stops`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(10, 20)),
            arrayOf(Element(0, 1, longArrayOf(10, 20), arrIsVars = false, indexOffset = 0)))
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 23
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val view = DefinitionalSweep.infer(problem, intArrayOf(1), emptyList(), stop)

        assertNull(view)
        assertEquals(23L, charged)
    }

    @Test
    fun `an explicit source relation should retain its inferred downstream definition cone`() {
        val problem = Problem(0, 5, Array(5) { IntDomain(0, 100) }, labsShapedFactors())
        val inferred = DefinitionalSweep.infer(problem, intArrayOf(2, 3, 4))
        val source = listOf(DefinitionHint.Product(2,
            DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)))
        val assignment = com.eignex.klause.solver.Assignment(0, 5)
        assignment.setInt(0, 2)
        assignment.setInt(1, 3)

        val sweep = assertNotNull(DefinitionalSweep.withSource(problem, inferred, source, Cancellation.Never))
        sweep.sweep(assignment, problem.finiteIntDomains(), problem.factors)

        assertEquals(6L, assignment.intValue(2))
        assertEquals(36L, assignment.intValue(4))
    }

    @Test
    fun `interrupted definition inference should discard the view without poisoning a fresh call`() {
        val problem = Problem(0, 5, Array(5) { IntDomain(0, 16) }, labsShapedFactors())
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 8
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val interrupted = DefinitionalSweep.infer(problem, intArrayOf(2, 3, 4), emptyList(), stop)
        val fresh = DefinitionalSweep.infer(problem, intArrayOf(2, 3, 4), emptyList(), Cancellation.Never)

        assertNull(interrupted)
        assertEquals(8L, charged)
        assertNotNull(fresh)
    }

    @Test
    fun `objective materialization should retain its containing work allowance`() {
        val problem = Problem(0, 5, Array(5) { IntDomain(0, 16) }, labsShapedFactors())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(2, 3, 4)))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 0, 1))
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 8
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val objective = sweep.functionalObjective(canonical, stop)

        assertNull(objective)
        assertEquals(8L, charged)
        assertNotNull(sweep.functionalObjective(canonical))
    }

    @Test
    fun `cancelled literal scope expansion should not affect a later scope`() {
        val problem = Problem(301, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(
            DefinitionalSweep.infer(
                problem,
                boolFolds = (0 until 300).map {
                    BoolFoldDefinition(it + 1, intArrayOf(Lit.make(it, true)), true)
                },
            ),
        )
        var polls = 0

        assertNull(sweep.expandUnitBooleanScope(intArrayOf(0), Cancellation { ++polls == 2 }))
        val expanded = assertNotNull(sweep.expandUnitBooleanScope(intArrayOf(0), Cancellation.Never))

        assertEquals((0..300).toList(), expanded.toList())
    }

    // Vars: x0=0, x1=1 (decisions); y=2 (=x0·x1); c=3 (=Σy, hinted); p=4 (=c·c, objective term).
    private fun labsShapedFactors(): Array<Factor> = arrayOf(
        Product(a = 0, b = 1, result = 2),
        Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 0),
        Product(a = 3, b = 3, result = 4),
    )

    @Test
    fun `nested element results should follow their selected inputs`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Element(0, 3, longArrayOf(2, 2), arrIsVars = true, indexOffset = 0),
                Element(0, 2, longArrayOf(1, 1), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(2, 3)))
        state.invariants = sweep.network(4, 0)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        val move = Move.IntSet(1, 1)
        val predicted = state.netDelta(move)

        state.apply(move)

        assertEquals(1L, state.assignment.intValue(2))
        assertEquals(1L, state.assignment.intValue(3))
        assertEquals(0L, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `nested element result repairs should reach an admissible source cell`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Element(0, 3, longArrayOf(2, 2), arrIsVars = true, indexOffset = 0),
                Element(0, 2, longArrayOf(1, 1), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(2, 3)))
        state.invariants = sweep.network(4, 0)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        state.moveSink.addChannelingIntSet(state, 3, 1)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)

        state.apply(move)

        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(1L, state.assignment.intValue(2))
        assertEquals(1L, state.assignment.intValue(3))
        assertEquals(0L, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `binary element results should retain their selection orientation under predicates`() {
        val problem = Problem(
            1,
            2,
            Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Element(0, 1, longArrayOf(0, 1), arrIsVars = false, indexOffset = 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.assignment.setInt(0, 0)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1)))
        state.invariants = sweep.network(2, 1)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()

        state.apply(Move.IntSet(0, 1))

        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(1L, state.cost)
        state.recompute()
        assertEquals(1L, state.cost)
    }

    @Test
    fun `element results with competing definitions should remain searched`() {
        val element = Element(0, 2, longArrayOf(0, 1), arrIsVars = false, indexOffset = 0)
        for (other in listOf<Factor>(
            Element(1, 2, longArrayOf(0, 1), arrIsVars = false, indexOffset = 0),
            Product(0, 1, 2),
        )) {
            val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(element, other))

            assertNull(DefinitionalSweep.infer(problem, intArrayOf(2)))
        }
    }

    @Test
    fun `cyclic element results should remain searched`() {
        for (factors in listOf(
            arrayOf<Factor>(Element(0, 1, longArrayOf(1, 1), arrIsVars = true, indexOffset = 0)),
            arrayOf<Factor>(Element(1, 1, longArrayOf(0, 1), arrIsVars = false, indexOffset = 0)),
            arrayOf<Factor>(
                Element(0, 1, longArrayOf(2, 2), arrIsVars = true, indexOffset = 0),
                Element(0, 2, longArrayOf(1, 1), arrIsVars = true, indexOffset = 0),
            ),
        )) {
            val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors)

            assertNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2)))
        }
    }

    @Test
    fun `element output domains should preserve incompatible selection violations`() {
        for (index in listOf(-1L, 0L, 1L)) {
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(-1, 1), IntDomain(0, 0)),
                arrayOf<Factor>(Element(0, 1, longArrayOf(1), arrIsVars = false, indexOffset = 0)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
            state.assignment.setInt(0, index)
            val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1)))
            state.invariants = sweep.network(2, 0)
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)

            state.recompute()

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `a functional objective over a product-capped sum should descend the decision vars`() {
        val sweep = assertNotNull(
            DefinitionalSweep.infer(labsShapedFactors(), numIntVars = 5, definedHints = intArrayOf(3)),
        )
        val obj = assertNotNull(
            sweep.functionalObjective(intArrayOf(4), longArrayOf(1L), constant = 0L, minimize = true),
        )
        assertEquals(setOf(0, 1), (obj as FunctionalObjective).leafVars.toSet())
        // p = (x0·x1)²: for x0=1, x1=-1 the objective is 1.
        assertEquals(1.0, obj.evaluate(Sample(BooleanArray(0), longArrayOf(1, -1, -1, -1, 1))))
    }

    @Test
    fun `a hinted sum over raw decision vars should be left searched`() {
        // Deriving a plain sum of decisions would remove it from search and stall repair.
        val factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0))
        assertNull(DefinitionalSweep.infer(factors, numIntVars = 3, definedHints = intArrayOf(2)))
    }

    @Test
    fun `a hinted element index should follow coordinate moves`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            Element(2, 3, longArrayOf(0, 1, 2, 3), arrIsVars = false, indexOffset = 0),
        )
        val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3))
        val problem = Problem(0, 4, domains, factors).bake()
        val sweep = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2)))
        val state = LocalSearchState(problem, Random(5))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.invariants = sweep.network(4, 0)
        state.recompute()

        state.apply(Move.IntSet(0, 1))
        state.apply(Move.IntSet(1, 0))

        assertEquals(2L, state.assignment.intValue(2))
        val cost = state.cost
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `a hinted equality index should follow coordinate moves and update its channels`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(6, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 8),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 1),
        )
        val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 2), IntDomain(0, 8), IntDomain(0, 1))
        val problem = Problem(1, 4, domains, factors)
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(2, 3))).network(4, 1)
        for (v in domains.indices) state.assignment.setInt(v, 0)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(Move.IntSet(0, 1))
        state.apply(Move.IntSet(1, 2))

        assertEquals(8L, state.assignment.intValue(2))
        assertEquals(true, state.assignment.boolValue(0))
        assertEquals(1L, state.assignment.intValue(3))
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `unhinted equality indexes should remain searched`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(6, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 8),
        )

        assertNull(DefinitionalSweep.infer(factors, 3))
    }

    @Test
    fun `an equality index constrained outside its definition should stay searched`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(6, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 8),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 7),
        )

        assertNull(DefinitionalSweep.infer(factors, 3, intArrayOf(2)))
    }

    @Test
    fun `an equality index with more than two inputs should stay searched`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(6, 2, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 8),
        )

        assertNull(DefinitionalSweep.infer(factors, 4, intArrayOf(3)))
    }

    @Test
    fun `an element index with another element role should stay searched`() {
        for (element in listOf(
            Element(2, 2, longArrayOf(0, 1, 2, 3), arrIsVars = false, indexOffset = 0),
            Element(2, 3, longArrayOf(0, 1, 2, 3), arrIsVars = true, indexOffset = 0),
        )) {
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                element,
            )

            assertNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2)))
        }
    }

    @Test
    fun `an affine coordinate constrained outside the index chain should stay searched`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, -1),
            Linear(intArrayOf(2, 1, -1), intArrayOf(2, 1, 3), LinearOp.EQ, 2),
            Element(3, 4, longArrayOf(0, 1, 2, 3), arrIsVars = false, indexOffset = 0),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 2),
        )
        val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 2), IntDomain(0, 3), IntDomain(0, 3))
        val state = LocalSearchState(Problem(0, 5, domains, factors).bake(), Random(5))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 1)
        state.assignment.setInt(3, 0)
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 5, intArrayOf(2, 3))).network(5, 0)
        state.recompute()

        state.apply(Move.IntSet(0, 1))

        assertEquals(1L, state.assignment.intValue(2))
    }

    @Test
    fun `unhinted extrema should remain searched`() {
        for (max in listOf(false, true)) {
            assertNull(DefinitionalSweep.infer(arrayOf<Factor>(ArrayMinMax(2, intArrayOf(0, 1), max)), 3))
        }
    }

    @Test
    fun `competing extremum definitions should leave their output searched`() {
        val factors = arrayOf<Factor>(ArrayMinMax(2, intArrayOf(0, 1), false), ArrayMinMax(2, intArrayOf(0, 1), true))
        assertNull(DefinitionalSweep.infer(factors, 3, intArrayOf(2)))
    }

    @Test
    fun `a cyclic extremum and alias cone should remain searched`() {
        val factors = arrayOf<Factor>(
            ArrayMinMax(2, intArrayOf(0, 3), false),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.EQ, 0),
        )
        assertNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2, 3)))
    }

    @Test
    fun `competing affine aliases should leave their output searched`() {
        val factors = arrayOf<Factor>(
            ArrayMinMax(2, intArrayOf(0, 1), false),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.EQ, 0),
            Linear(intArrayOf(1, -1), intArrayOf(3, 0), LinearOp.EQ, 0),
        )
        val net = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2, 3))).network(4, 0)
        assertEquals(false, net.isDefinedInt(3))
        assertEquals(true, net.isDefinedInt(2))
    }
}
