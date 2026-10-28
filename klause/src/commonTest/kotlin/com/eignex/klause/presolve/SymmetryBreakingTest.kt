package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.invariantProjection
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.conditionedRoot
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SymmetryBreakingTest {
    private fun isFeasible(problem: Problem, bools: BooleanArray, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun countFeasible(problem: Problem): Int {
        val b = problem.numBoolVars
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        var count = 0
        while (true) {
            for (mask in 0 until (1 shl b).coerceAtLeast(1)) {
                val bools = BooleanArray(b) { (mask shr it) and 1 == 1 }
                if (isFeasible(problem, bools, ints.copyOf())) count++
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun broken(problem: Problem): Problem =
        problem.bake().let { it.withPassDelta(Presolve.breakSymmetries(it), BakeConfig.NONE) }

    private fun precedence(problem: Problem): Problem =
        problem.bake().let { it.withPassDelta(Presolve.breakValuePrecedence(it), BakeConfig.NONE) }

    private fun checkSound(name: String, problem: Problem, expectReduced: Boolean) {
        val broken = broken(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after <= orig, "$name: breaking ADDED solutions ($orig -> $after)")
        assertEquals(orig > 0, after > 0, "$name: breaking changed satisfiability ($orig -> $after)")
        if (expectReduced) {
            assertTrue(after < orig, "$name: expected fewer solutions but $orig -> $after")
        } else {
            assertTrue(Presolve.breakSymmetries(problem.bake()).isEmpty, "$name: expected no symmetry detected")
        }
    }

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `unread finite columns should receive no symmetry factors`() {
        val problem = Problem(3, 3, Array(3) { IntDomain(0, 2) }, emptyList()).bake()

        assertTrue(Presolve.breakSymmetries(problem).isEmpty)
        assertTrue(Presolve.breakValuePrecedence(problem).isEmpty)
    }

    @Test
    fun `an interrupted active column scan should post no symmetry break`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, listOf(AllDifferent(intArrayOf(1, 3)))).bake()

        for (pass in listOf(PresolvePass.BREAK_SYMMETRIES, PresolvePass.VALUE_PRECEDENCE)) {
            val budget = PresolveBudget(6)
            val context = PresolveContext(cancellation = budget.orSpent(Cancellation.Never))

            assertTrue(pass.applyFinite(problem, context).isEmpty)
        }
    }

    @Test
    fun `solution set sensitive preparation should retain live symmetry assignments`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, listOf(AllDifferent(intArrayOf(0, 2)))).bake()

        val result = Presolver.run(
            problem, PresolveConfig.parse("off,+symmetry,+value-precede"), PresolveContext(solutionSetSensitive = true),
        )

        assertTrue(result.passesFired.isEmpty())
        assertEquals(countFeasible(problem), countFeasible(result.problem))
    }

    @Test
    fun `live scalar symmetries should exclude unread integer and Boolean columns`() {
        val problem = Problem(
            4,
            4,
            Array(4) { IntDomain(0, 2) },
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 2),
                Clause(intArrayOf(pos(1), pos(3))),
            ),
        ).bake()

        val delta = Presolve.breakSymmetries(problem)

        assertTrue(delta.addedFactors.any { it is SymmetryHandling })
        assertTrue(delta.addedFactors.any { it is Linear })
        assertTrue(delta.addedFactors.any { it is Clause })
        assertTrue(delta.addedFactors.all { factor ->
            factor.intVars.all { it == 1 || it == 3 } && factor.boolVars.all { it == 1 || it == 3 }
        })
    }

    @Test
    fun `active objective columns should remain fixed in finite symmetry discovery`() {
        val problem = Problem(
            4,
            4,
            Array(4) { IntDomain(0, 2) },
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 2),
                Clause(intArrayOf(pos(1), pos(3))),
            ),
        ).bake()

        val delta = Presolve.breakSymmetries(problem, objectiveIntVars = setOf(1), objectiveBoolVars = setOf(1))

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `objective symmetry should prune complete search while retaining each attainable cost`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 3) }, listOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        for (weight in listOf(-2L, 2L)) {
            val objective = LinearObjective(intCoefficients = LongArray(3) { weight }, constant = 7)
            val context = PresolveContext.of(objective, transformObjective = true)
            val delta = PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context)
            val reduced = problem.withPassDelta(delta, BakeConfig.NONE)

            val original = BacktrackSolver(problem).enumerate().toList()
            val representatives = BacktrackSolver(reduced).enumerate().toList()

            assertEquals(24, original.size)
            assertEquals(4, representatives.size)
            assertEquals(
                original.map(objective::evaluateLong).toSet(), representatives.map(objective::evaluateLong).toSet(),
            )
            assertTrue(delta.addedFactors.none { it is ValuePrecede })
        }
    }

    @Test
    fun `objective symmetry should admit the Latin transpose without scalar pair orders`() {
        val factors = (0 until 4).flatMap { i -> listOf(
            AllDifferent(IntArray(4) { j -> 4 * i + j }),
            AllDifferent(IntArray(4) { j -> 4 * j + i }),
        ) }
        val problem = Problem(0, 16, Array(16) { IntDomain(0, 3) }, factors).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(8, 1, 1, 1, 1, 4, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1))
        val context = PresolveContext.of(objective, transformObjective = true)

        val delta = PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context)

        val symmetry = delta.addedFactors.filterIsInstance<SymmetryHandling>().single()
        val transpose = IntArray(16) { i -> 4 * (i % 4) + i / 4 }
        assertTrue(symmetry.generators.any { it.first.contentEquals(transpose) })
        assertEquals(1, delta.addedFactors.size)
        val point = longArrayOf(0, 1, 2, 3, 2, 3, 0, 1, 1, 2, 3, 0, 3, 0, 1, 2)
        val image = LongArray(16) { point[transpose[it]] }
        val singlePair = point.copyOf().apply { this[1] = point[4]; this[4] = point[1] }
        assertFalse(isFeasible(problem, BooleanArray(0), singlePair))
        assertTrue(isFeasible(problem, BooleanArray(0), image))
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        assertTrue(isFeasible(reduced, BooleanArray(0), point))
        assertFalse(isFeasible(reduced, BooleanArray(0), image))
    }

    @Test
    fun `objective symmetry should retain signed Boolean weights and offset`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1), pos(2))))).bake()
        for (weight in listOf(-3L, 3L)) {
            val objective = LinearObjective(boolWeights = LongArray(3) { weight }, constant = -5)
            val delta = PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(
                problem, PresolveContext.of(objective, transformObjective = true),
            )
            val original = BacktrackSolver(problem).enumerate().toList()
            val representatives = BacktrackSolver(problem.withPassDelta(delta, BakeConfig.NONE)).enumerate().toList()

            assertEquals(7, original.size)
            assertEquals(3, representatives.size)
            assertEquals(
                original.map(objective::evaluateLong).toSet(), representatives.map(objective::evaluateLong).toSet(),
            )
        }
    }

    @Test
    fun `objective symmetry should distinguish signed coefficients`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 3) }, listOf(AllDifferent(intArrayOf(0, 1)))).bake()
        for (coefficients in listOf(longArrayOf(-1, 1), longArrayOf(1, 2), longArrayOf(0, 1))) {
            val context = PresolveContext.of(LinearObjective(intCoefficients = coefficients), transformObjective = true)

            assertTrue(PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context).isEmpty)
        }
    }

    @Test
    fun `objective symmetry should require exact nonsensitive unannotated admission`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 3) }, listOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val exact = PresolveContext.of(objective, transformObjective = true)
        val pass = PresolvePass.BREAK_OBJECTIVE_SYMMETRIES

        for (context in listOf(
            PresolveContext.of(objective),
            exact.copy(solutionSetSensitive = true),
            exact.copy(modelBreaksSymmetry = true),
        )) assertTrue(pass.applyFinite(problem, context).isEmpty)
        assertFalse(PresolveConfig.DEFAULT.resolved(pass, exact))
        assertFalse(PresolveConfig.parse("default,+objective-symmetry").forLocalSearch().resolved(pass, exact))
        assertFalse(PresolveConfig.parse("off,+objective-symmetry").resolved(
            pass, exact.copy(solutionSetSensitive = true),
        ))
    }

    @Test
    fun `objective symmetry should preserve root holes and opening pins`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val context = PresolveContext.of(objective, transformObjective = true)
        val factors = listOf(AllDifferent(intArrayOf(0, 1)))
        val pinned = Problem(0, 2, Array(2) { IntDomain(0, 3) }, factors).bake()
            .conditionedRoot(Assumptions.None.withInt(0, 3), Cancellation.Never)
        val holes = Problem(0, 2, arrayOf(IntDomain(0, 3).excludeValue(1), IntDomain(0, 3)), factors).bake()

        for (problem in listOf(pinned, holes)) {
            assertTrue(PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context).isEmpty)
        }
    }

    @Test
    fun `value protection should follow the exact objective rather than stale support metadata`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(5, 7)),
            listOf(AllDifferent(intArrayOf(0, 1)), Table(intArrayOf(2), longArrayOf(5, 6, 7))),
        ).bake()
        val context = PresolveContext(
            objectiveIntCoeffs = mapOf(0 to 1L),
            linearObjective = LinearObjective(intCoefficients = longArrayOf(0, 0, -1)),
        )

        val delta = PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context)
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)

        assertTrue(isFeasible(reduced, BooleanArray(0), longArrayOf(0, 1, 7)))
    }

    @Test
    fun `objective symmetry should keep unread objective columns fixed`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 3) }, listOf(AllDifferent(intArrayOf(0, 2)))).bake()
        val context = PresolveContext.of(
            LinearObjective(intCoefficients = longArrayOf(1, 1, 1)), transformObjective = true,
        )

        val delta = PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context)

        assertTrue(delta.addedFactors.any { it is SymmetryHandling })
        assertTrue(delta.addedFactors.all { 1 !in it.intVars })
    }

    @Test
    fun `an interrupted objective symmetry scan should post no break`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 3) }, listOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        val budget = PresolveBudget(1)
        val context = PresolveContext.of(
            LinearObjective(intCoefficients = longArrayOf(1, 1, 1)), transformObjective = true,
        )
            .withCancellation(budget.orSpent(Cancellation.Never))

        assertTrue(PresolvePass.BREAK_OBJECTIVE_SYMMETRIES.applyFinite(problem, context).isEmpty)
        assertTrue(budget.spent() > 0)
    }

    @Test
    fun `newly exposed value orbits should retain an optimal objective assignment`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(1, 1), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(AllDifferent(intArrayOf(1, 2, 3))),
        ).bake()
        val optimal = longArrayOf(1, 0, 1, 2)

        for (delta in listOf(
            Presolve.breakSymmetries(problem, objectiveIntVars = setOf(1)),
            Presolve.breakValuePrecedence(problem, objectiveIntVars = setOf(1)),
        )) {
            val reduced = problem.withPassDelta(delta, BakeConfig.NONE)

            assertTrue(isFeasible(reduced, BooleanArray(0), optimal))
        }
    }

    @Test
    fun `unread domains should neither split value orbits nor enter precedence chains`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 2)),
            listOf(AllDifferent(intArrayOf(0, 2))),
        ).bake()

        for (delta in listOf(Presolve.breakSymmetries(problem), Presolve.breakValuePrecedence(problem))) {
            val chains = delta.addedFactors.filterIsInstance<ValuePrecede>()

            assertEquals(setOf(0L to 1L, 1L to 2L), chains.map { it.s to it.t }.toSet())
            assertTrue(chains.all { it.xs.contentEquals(intArrayOf(0, 2)) })
        }
    }

    @Test
    fun `a value symmetry pin should select a live column`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) }, listOf(Table(intArrayOf(1), longArrayOf(0, 1, 2))),
        ).bake()

        val delta = Presolve.breakSymmetries(problem)

        assertEquals(1, delta.addedFactors.size)
        assertTrue(delta.addedFactors.single().intVars.contentEquals(intArrayOf(1)))
    }

    @Test
    fun `affine reconstructed columns should stay absent from symmetry factors`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 2),
            ),
        ).bake()
        val affine = Presolve.eliminateAffineSingletons(problem)
        val residual = problem.withPassDelta(affine, BakeConfig.NONE).bake()

        val symmetry = Presolve.breakSymmetries(residual)

        val rebuilt = affine.rebuild.steps.filterIsInstance<RebuildStep.AffineValue>().map { it.variable }.toSet()
        assertEquals(setOf(0, 1), rebuilt)
        assertTrue(residual.factors.all { factor -> factor.intVars.all { it == 2 } })
        assertTrue(symmetry.addedFactors.all { factor -> factor.intVars.all { it == 2 } })
    }

    @Test
    fun `symmetry breaking should keep a row-symmetric matrix satisfiable`() {
        // Ordering each column orbit independently would cut both solutions of this single orbit.
        fun eq(a: Int, b: Int) = listOf(
            Clause(intArrayOf(Lit.make(a, false), Lit.make(b, true))),
            Clause(intArrayOf(Lit.make(a, true), Lit.make(b, false))),
        )
        fun neq(a: Int, b: Int) = listOf(
            Clause(intArrayOf(Lit.make(a, true), Lit.make(b, true))),
            Clause(intArrayOf(Lit.make(a, false), Lit.make(b, false))),
        )
        val problem = Problem(4, 0, emptyArray(), eq(0, 3) + eq(1, 2) + neq(0, 1) + neq(2, 3))
        val broken = broken(problem)
        assertEquals(
            countFeasible(problem) > 0,
            countFeasible(broken) > 0,
            "breaking made a satisfiable orbit UNSAT",
        )
    }

    @Test
    fun `generator search should break a composite bool-int symmetry`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
                ReifiedLinear(
                    auxBoolVar = 1,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(1),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
            ),
        )
        checkSound("composite bool-int", problem, expectReduced = true)
    }

    @Test
    fun `value precedence should keep one solution per value-symmetry class`() {
        // Bell(3) = 5 set partitions of three variables.
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) }, List(3) { Table(intArrayOf(it), longArrayOf(0, 1, 2)) },
        )
        val broken = precedence(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after < orig, "expected reduction: $orig -> $after")
        assertEquals(orig > 0, after > 0)
        assertEquals(5, after)
    }

    @Test
    fun `an ordering linear should block value precedence`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        assertTrue(Presolve.breakValuePrecedence(problem.bake()).isEmpty)
    }

    @Test
    fun `interchangeable matrix rows should be lex-ordered`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(2, 3), LinearOp.LE, 3),
            ),
        )
        checkSound("matrix-rows", problem, expectReduced = true)
    }

    @Test
    fun `interchangeable vars in separate isomorphic factors should be ordered`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
        )
        checkSound("cross-factor", problem, expectReduced = true)
    }

    @Test
    fun `vars in asymmetric separate factors should not be grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )
        checkSound("asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `vars with unequal coefficients should not be grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("unequalCoeff", problem, expectReduced = false)
    }

    @Test
    fun `bool row symmetry should be broken by a propagator-only SymmetryHandling factor`() {
        val problem = Problem(
            6,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(longArrayOf(1, 2, 4), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 5L),
                PseudoBoolean(longArrayOf(1, 2, 4), intArrayOf(pos(3), pos(4), pos(5)), PbOp.LE, 5L),
            ),
        )
        val broken = broken(problem)
        assertEquals(problem.numIntVars, broken.numIntVars)
        val symmetry = broken.factors.filterIsInstance<SymmetryHandling>().single()
        assertSame(NoInvariant, symmetry.invariantProjection())
    }

    @Test
    fun `a non-relabelable table should block symmetry breaking`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1))),
        )
        checkSound("table-asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `booleans fixed apart by dropped factors should not be ordered against each other`() {
        val units = listOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(1, false))))
        val session = PresolveSession(Problem(2, 0, emptyArray(), units).bake())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0, 1))))

        assertTrue(session.applyDelta(Presolve.breakSymmetries(session.passInput())))
    }

    @Test
    fun `vars with different domains should not be grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("differentDomains", problem, expectReduced = false)
    }

    @Test
    fun `an asymmetric regular should have no value symmetry`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 2)),
            listOf(
                Regular(
                    intArrayOf(0),
                    numStates = 1,
                    alphabetSize = 2,
                    transitions = longArrayOf(1, 0),
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
            ),
        )
        checkSound("regular-asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `a wide row remap hash should match its remapped structural key`() {
        val wide = parseBigInt("18446744073709551616")
        val row = ReifiedLinear(0, intArrayOf(0, 1, 2), arrayOf(wide, -wide, BIG_ONE), LinearOp.LE, wide)
        val mapping = VarRemap(intArrayOf(1, 0), intArrayOf(2, 0, 1))

        val hashes = List(3) { row.remapStructuralHash(mapping) }

        assertEquals(List(3) { row.remap(mapping).structuralKey().hashCode() }, hashes)
    }

    private fun sourceBroken(problem: Problem): Problem =
        assertNotNull(problem.withSourcePassDelta(Presolve.breakSourceSymmetries(problem)))

    private fun openSum(openX: Boolean, openY: Boolean, yLow: Long = 0) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 4), IntDomain(yLow, 4)),
        factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)),
        openIntHi = booleanArrayOf(openX, openY),
    )

    private fun openColoring(zLow: Long) = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(zLow, 3)),
        factors = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.NE, 0),
        ),
        openIntHi = booleanArrayOf(false, false, true),
    )

    @Test
    fun `source symmetry breaking should order interchangeable open columns`() {
        val problem = openSum(openX = true, openY = true)

        val after = countFeasible(sourceBroken(problem))

        assertEquals(5, countFeasible(problem))
        assertEquals(3, after, "x <= y keeps one of each swapped pair")
    }

    @Test
    fun `source symmetry breaking should keep columns with different open declarations apart`() {
        listOf(
            openSum(openX = true, openY = false),
            openSum(openX = true, openY = true, yLow = 1),
        ).forEach { problem ->
            assertTrue(Presolve.breakSourceSymmetries(problem).isEmpty)
        }
    }

    @Test
    fun `source symmetry breaking should post no propagator-only break over an open column`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
                ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), LinearOp.EQ, bound = 1),
                ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(1), LinearOp.EQ, bound = 1),
            ),
            openIntHi = booleanArrayOf(true, true),
        )

        val delta = Presolve.breakSourceSymmetries(problem)

        assertTrue(delta.addedFactors.none { it is SymmetryHandling })
    }

    @Test
    fun `an open column admitting part of the values should split their orbit`() {
        assertTrue(Presolve.breakSourceSymmetries(openColoring(zLow = 1)).isEmpty)
    }
}
