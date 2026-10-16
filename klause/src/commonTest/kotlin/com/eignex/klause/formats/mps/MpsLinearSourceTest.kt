package com.eignex.klause.formats.mps

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.checkLinearSourceWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsLinearSourceTest {
    @Test
    fun `decimal rows and maximized objectives should retain their original units`() {
        val parsed = Mps.parse(
            "OBJSENSE\n MAX\nROWS\n N COST\n G R\nCOLUMNS\n" +
                " X COST 9007199254740993 R 0.2\nRHS\n RHS COST 0.7 R 0.3\nENDATA",
        )

        val source = assertNotNull(parsed.toLinearSourceProblem())

        assertEquals(NumericAuthority.DECIMAL_LITERALS, source.semantics.declared.numericAuthority)
        assertEquals(SemanticRational.of(BIG_ONE, bigIntOf(5)), source.rows.single().terms.single().coefficient)
        assertEquals(SemanticRational.of(bigIntOf(3), bigIntOf(10)), source.rows.single().lower)
        assertNull(source.rows.single().upper)
        val objective = assertNotNull(source.objective)
        assertEquals(ObjectiveSense.MAXIMIZE, objective.sense)
        assertEquals(SemanticRational.of(parseBigInt("9007199254740993")), objective.terms.single().coefficient)
        assertEquals(SemanticRational.of(bigIntOf(-7), bigIntOf(10)), objective.constant)
        assertEquals(mapOf("X" to 0), source.realVarNames)
    }

    @Test
    fun `caller defaults should replace only wholly unranged source declarations`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n M1 'MARKER' 'INTEND'\n" +
                " X COST 1\n Y COST 1\nBOUNDS\n UP B Y 5\nENDATA",
        )
        val third = SemanticRational.of(BIG_ONE, bigIntOf(3))
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(BoundOverride.Value(Long.MIN_VALUE),
                BoundOverride.Value(Long.MAX_VALUE)),
            undeclaredReals = BoundsOverride(BoundOverride.Value(third), BoundOverride.Open),
        )

        val source = assertNotNull(parsed.toLinearSourceProblem(overrides))

        assertEquals(SemanticRational.ofLong(Long.MIN_VALUE), source.variables[0].lower)
        assertEquals(SemanticRational.ofLong(Long.MAX_VALUE), source.variables[0].upper)
        assertEquals(third, source.variables[1].lower)
        assertNull(source.variables[1].upper)
        assertEquals(SemanticRational.ZERO, source.variables[2].lower)
        assertEquals(SemanticRational.ofLong(5), source.variables[2].upper)
        assertEquals(mapOf("I" to 0), source.intVarNames)
        assertEquals(mapOf("X" to 0, "Y" to 1), source.realVarNames)
        assertEquals(0.0, parsed.variables[1].lower)
    }

    @Test
    fun `literal caller bounds should remain finite beyond the text infinity threshold`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n X COST 1\n Y COST 1\n" +
                "BOUNDS\n UP B Y 100000000000000000000\nENDATA",
        )
        val bound = SemanticRational.of(parseBigInt("1000000000000000000000000000000"))
        val overrides = SemanticOverrides(undeclaredReals = BoundsOverride(upper = BoundOverride.Value(bound)))

        val source = assertNotNull(parsed.toLinearSourceProblem(overrides))

        assertEquals(bound, source.variables[0].upper)
        assertNull(source.variables[1].upper)
    }

    @Test
    fun `source capture should retain coefficients after parsed array mutation`() {
        val parsed = Mps.parse("ROWS\n N COST\n L R\nCOLUMNS\n X COST 0.1 R 0.2\nRHS\n RHS R 0.3\nENDATA")
        val source = assertNotNull(parsed.toLinearSourceProblem())

        parsed.objective.coeffs[0] = 7.0
        parsed.constraints[0].coeffs[0] = 9.0

        assertEquals(SemanticRational.of(BIG_ONE, bigIntOf(10)),
            assertNotNull(source.objective).terms.single().coefficient)
        assertEquals(SemanticRational.of(BIG_ONE, bigIntOf(5)), source.rows.single().terms.single().coefficient)
        val edited = assertNotNull(parsed.toLinearSourceProblem())
        assertEquals(SemanticRational.ofLong(7), assertNotNull(edited.objective).terms.single().coefficient)
        assertEquals(SemanticRational.ofLong(9), edited.rows.single().terms.single().coefficient)
    }

    @Test
    fun `guarded duplicate terms should retain their independent tolerance magnitudes`() {
        val parsed = MpsModel("duplicates", ObjectiveSense.MINIMIZE,
            MpsObjective("", intArrayOf(), doubleArrayOf(), 0.0),
            listOf(MpsVar("B", true, 0.0, 2.0), MpsVar("X", false, null, null)),
            listOf(MpsConstraint("R", intArrayOf(1, 1), doubleArrayOf(1.0, -1.0), null, 1.0,
                MpsIndicator(0, false))))

        val source = assertNotNull(parsed.toLinearSourceProblem())

        val row = source.rows.single()
        assertEquals(listOf(1, 1), row.terms.map { it.column })
        assertEquals(listOf(SemanticRational.ONE, SemanticRational.ofLong(-1)), row.terms.map { it.coefficient })
        assertEquals(0, assertNotNull(row.indicator).column)
        assertEquals(false, assertNotNull(row.indicator).whenOne)
        assertNull(source.objective)
    }

    @Test
    fun `exact and tolerant policies should retain the same original decimal representation`() {
        val parsed = Mps.parse("ROWS\n N COST\n E R\nCOLUMNS\n X COST 0.1 R 0.2\nRHS\n RHS R 0.3\nENDATA")
        val policies = listOf(SourceTolerance(), SourceTolerance(absolute = SemanticRational.ONE))

        val sources = policies.map { policy ->
            assertNotNull(parsed.toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = policy)))
        }

        assertEquals(policies, sources.map { it.semantics.effective.feasibilityTolerance })
        for (source in sources) {
            assertEquals(SemanticRational.of(BIG_ONE, bigIntOf(5)), source.rows.single().terms.single().coefficient)
            assertEquals(SemanticRational.of(bigIntOf(3), bigIntOf(10)), source.rows.single().lower)
        }
    }

    @Test
    fun `programmatic coefficients should retain their exact binary64 authority`() {
        val source = MpsModel("binary", ObjectiveSense.MINIMIZE,
            MpsObjective("COST", intArrayOf(0), doubleArrayOf(0.1), 0.0),
            listOf(MpsVar("X", false, null, null)), emptyList())

        val captured = assertNotNull(source.toLinearSourceProblem())

        assertEquals(source.semantics.numericAuthority, captured.semantics.declared.numericAuthority)
        val coefficient = assertNotNull(captured.objective).terms.single().coefficient
        assertEquals(BigFraction.ofDouble(0.1), BigFraction.of(coefficient.numerator, coefficient.denominator))
    }

    @Test
    fun `source tolerance should apply before decimal row normalization`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n X COST 0.1 R 0.3\nRHS\n RHS COST 0.7 R 1\nENDATA",
        )
        val policy = SourceTolerance(absolute = SemanticRational.of(BIG_ONE, bigIntOf(5)))
        val source = assertNotNull(parsed.toLinearSourceProblem(SemanticOverrides(feasibilityTolerance = policy)))

        val checked = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(3)), Cancellation.Never)

        val witness = assertIs<LinearSourceCheck.Accepted>(checked).witness
        assertFalse(witness.exact)
        assertEquals(BigFraction.of(bigIntOf(-2), bigIntOf(5)), witness.objective)
        val exact = assertNotNull(parsed.toLinearSourceProblem(
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
        ))
        assertIs<LinearSourceCheck.Rejected>(
            checkLinearSourceWitness(exact, listOf(BigFraction.ofLong(3)), Cancellation.Never),
        )
    }

    @Test
    fun `zero tolerance should retain admitted numerical finite completion`() {
        val parsed = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n UP B X 1\nENDATA")
        val policies = listOf(SemanticOverrides(), SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        for (policy in policies) {
            val source = assertNotNull(parsed.toLinearSourceProblem(policy))
            for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH)) {
                val invocation = SourceSetup.prepare(SourceSetupRequest(
                    SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine,
                ))

                assertEquals(SourceExecutionKind.FINITE, SourceSetup.executionKind(invocation))
            }
        }
    }

    @Test
    fun `interrupted source capture should withhold the entire neutral source`() {
        val parsed = Mps.parse("ROWS\n L R\nCOLUMNS\n X R 1\n Y R 1\nRHS\n RHS R 2\nENDATA")
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 3L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val captured = parsed.toLinearSourceProblem(cancellation = stop)

        assertNull(captured)
        assertTrue(charged >= 3L)
    }
}
