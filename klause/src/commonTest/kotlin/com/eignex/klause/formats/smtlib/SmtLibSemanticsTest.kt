package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmtLibSemanticsTest {
    @Test
    fun `both factor parse entries should capture exact open declaration semantics`() {
        val text = "(declare-const x Int) (declare-const y Real)"

        val sources = listOf(SmtLib.parse(text), SmtLib.parse(StringCharSource(text)))

        for (source in sources) {
            assertSame(source.model.semantics, source.semantics)
            assertEquals(smtLibSemantics, source.semantics.declared)
            assertEquals(smtLibSemantics, source.semantics.effective)
            assertTrue(source.model.intBounds.isOpenLower(0))
            assertTrue(source.model.intBounds.isOpenUpper(0))
            assertFalse(source.semantics.isApproximate)
        }
    }

    @Test
    fun `finite Long extrema should remain distinct from open integer bounds`() {
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
            BoundOverride.Value(Long.MIN_VALUE), BoundOverride.Value(Long.MAX_VALUE),
        ))

        val source = SmtLib.parse("(declare-const x Int)", semanticOverrides = overrides)

        assertFalse(source.model.intBounds.isOpenLower(0))
        assertFalse(source.model.intBounds.isOpenUpper(0))
        assertEquals(Long.MIN_VALUE, source.model.intBounds.lower(0))
        assertEquals(Long.MAX_VALUE, source.model.intBounds.upper(0))
    }

    @Test
    fun `legacy integer ranges should constrain source declarations after bound inference`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (= x 3))", unboundedIntLo = 0, unboundedIntHi = 2)

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(UndeclaredIntegerBounds(0, 2), source.semantics.effective.undeclaredIntegers)
    }

    @Test
    fun `explicit open replacements should override legacy finite endpoints`() {
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(BoundOverride.Open, BoundOverride.Open))

        val source = SmtLib.parse("(declare-const x Int)", 0, 2, semanticOverrides = overrides)

        assertTrue(source.model.intBounds.isOpenLower(0))
        assertTrue(source.model.intBounds.isOpenUpper(0))
        assertEquals(smtLibSemantics, source.semantics.effective)
    }

    @Test
    fun `rational real declaration endpoints should preserve exact source feasibility`() {
        val third = SemanticRational.of(bigIntOf(1), bigIntOf(3))
        val overrides = SemanticOverrides(undeclaredReals = BoundsOverride(BoundOverride.Value(third), BoundOverride.Value(third)))
        val source = SmtLib.parse("(declare-const x Real) (assert (< x (/ 1 3)))", semanticOverrides = overrides)

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(third, source.semantics.effective.undeclaredReals?.lower)
    }

    @Test
    fun `polynomial fallback should enforce caller bounds beside original nonlinear constraints`() {
        val overrides = SemanticOverrides(undeclaredReals = BoundsOverride(upper = BoundOverride.Value(SemanticRational.ZERO)))
        val input = SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 1)) (assert (> x 0))",
            semanticOverrides = overrides,
        )
        val source = assertIs<SmtLibInput.Polynomial>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(SemanticRational.ZERO, source.semantics.effective.undeclaredReals?.upper)
    }

    @Test
    fun `nonlinear integer fallback should enforce caller declaration bounds`() {
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(0L)))
        val input = SmtLibAdmission.parse(
            "(declare-const x Int) (define-fun square ((y Int)) Int (* y y)) " +
                "(assert (= (square x) 1)) (assert (> x 0))",
            semanticOverrides = overrides,
        )
        val source = assertIs<SmtLibInput.NonlinearInteger>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(0L, source.semantics.effective.undeclaredIntegers?.upper)
    }

    @Test
    fun `function fallback should enforce exact rational bounds on original declarations`() {
        val third = SemanticRational.of(bigIntOf(1), bigIntOf(3))
        val overrides = SemanticOverrides(undeclaredReals = BoundsOverride(upper = BoundOverride.Value(third)))
        val input = SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (exp 0) 1)) (assert (> x (/ 1 3)))",
            semanticOverrides = overrides,
        )
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(third, source.semantics.effective.undeclaredReals?.upper)
    }

    @Test
    fun `SMT entries should reject inapplicable or approximate semantic replacements before reading input`() {
        val replacements = listOf(
            SemanticOverrides(floats = FloatSemantics.Grid(10, 100)),
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
            SemanticOverrides(pinAbsentOptionalValues = false),
        )

        for (replacement in replacements) {
            assertFailsWith<IllegalArgumentException> { SmtLib.parse("", semanticOverrides = replacement) }
            var opened = false
            assertFailsWith<IllegalArgumentException> {
                SmtLibAdmission.parse({ opened = true; StringCharSource("") }, semanticOverrides = replacement)
            }
            assertFalse(opened)
        }
    }

    @Test
    fun `SMT builder should capture ambient resources consistently with public entries`() {
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(largeSpanThreshold = 17)

            val source = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false).build()

            assertEquals(SmtLib.parse("").model.settings, source.model.settings)
            assertEquals(17L, source.model.settings.largeSpanThreshold)
        } finally {
            KlauseConfig.current = saved
        }
    }
}
