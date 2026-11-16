package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtSourceModelTest {
    @Test
    fun `original declaration order and spellings should survive exact fallback`() {
        val source = SmtLib.parse("(declare-const |result| Real) (declare-const flag Bool) " +
            "(assert (= (* result result) 2.0))")

        val model = source.reconstructModel({ true }, { error("unexpected integer") },
            { "(root-obj (+ (^ x 2) (- 2)) 2)" })

        assertEquals(listOf("|result|", "flag"), model.values.map { it.first.name })
        assertTrue(model.toSmtLib().contains("(define-fun |result| () Real (root-obj"))
    }

    @Test
    fun `composite source binding should survive reduction solving and original checks`() {
        val original = captureSmtSource(SExprReader("(declare-const word (_ BitVec 2))").readAll(), Cancellation.Never)
        val encoded = SExprReader("(declare-const low Bool) (declare-const high Bool) " +
            "(assert (not low)) (assert high)").readAll()
        val declaration = original.declarations.single()
        var checked = false
        val reduction = original.copy(commands = encoded,
            bindings = listOf(SmtSourceBinding(declaration, SmtReconstruction.Bits(listOf("low", "high")))),
            checks = listOf(SmtSourceCheck { values ->
                checked = true
                values.value("word") == SmtValue.BitVector("10")
            }))
        val source = lowerSmtReduction(reduction, Long.MIN_VALUE, Long.MAX_VALUE, ProblemSettings(),
            Cancellation.Never, smtLibSemantics.resolve())
        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment

        val model = source.reconstructModel(assignment::boolValue, assignment::intValue, assignment::realValue)

        assertTrue(checked)
        assertEquals("(\n  (define-fun word () (_ BitVec 2) #b10)\n)", model.toSmtLib())
        assertEquals(listOf(declaration), source.originalSource?.declarations)
    }

    @Test
    fun `failed original checks should withhold reconstructed models`() {
        val source = SmtLib.parse("(declare-const flag Bool) (assert flag)")
            .copy(sourceChecks = listOf(SmtSourceCheck { false }))

        assertFailsWith<IllegalArgumentException> {
            source.reconstructModel({ true }, { "0" }, { "0.0" })
        }
    }

    @Test
    fun `source endpoint overrides should not constrain generated declarations`() {
        val original = captureSmtSource(SExprReader("(declare-const x Int)").readAll(), Cancellation.Never)
        val reduction = original.copy(commands = SExprReader("(declare-const x Int) " +
            "(declare-const private Int) (assert (= x 1)) (assert (= private 100))").readAll())
        val source = lowerSmtReduction(reduction, 0, 2, ProblemSettings(), Cancellation.Never,
            resolveSmtLibSemantics(0, 2))

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment

        assertEquals("100", assignment.intValue(source.encodingIntVarNames.getValue("private")))
        assertEquals(setOf("x"), source.intVarNames.keys)
        assertEquals(listOf("x"), source.reconstructModel(assignment::boolValue, assignment::intValue,
            assignment::realValue).values.map { it.first.name })
    }

    @Test
    fun `composed private bindings should stay out of original models`() {
        val source = SmtLib.parse("(declare-const flag Bool) (assert flag)")
        val declaration = SmtSourceDeclaration("word", emptyList(), SmtSort.BitVector(1))
        val composite = source.copy(sourceBindings = listOf(SmtSourceBinding(declaration,
            SmtReconstruction { values -> values.value("private") })),
            privateBindings = mapOf("private" to SmtReconstruction.Bits(listOf("flag"))))

        val model = composite.reconstructModel({ true }, { "0" }, { "0.0" })

        assertEquals(listOf(declaration to SmtValue.BitVector("1")), model.values)
    }

    @Test
    fun `cancelled reconstruction should not publish a partial model`() {
        val source = SmtLib.parse("(declare-const flag Bool)")

        assertFailsWith<CancellationException> {
            source.reconstructModel({ true }, { "0" }, { "0.0" }, Cancellation { true })
        }
    }
}
