package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.SourceFiniteControls
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.solve
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtLibBitVectorTest {
    @Test
    fun `source solving should publish only original width correct bitvector declarations`() {
        val parsed = SmtLib.parse("(declare-const |word| (_ BitVec 129)) " +
            "(assert (= word #b${"1".repeat(129)})) " +
            "(assert (= (bvadd word (_ bv1 129)) (_ bv0 129)))")
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val result = FinitePipeline.solve(FiniteSolveRequest(invocation.finiteShape(),
            SourceFiniteControls.Backtrack(BacktrackParams())), FiniteSolveCallbacks({}, {}))

        val outcome = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(FiniteSolveVerdict.SAT, outcome.verdict)
        val model = parsed.reconstructModel(assertNotNull(outcome.bestSample))
        assertEquals(listOf("|word|"), model.values.map { it.first.name })
        assertEquals(SmtValue.BitVector("1".repeat(129)), model.values.single().second)
        assertTrue(parsed.boolVarNames.isEmpty())
        assertTrue(parsed.sourceChecks.isNotEmpty())
    }

    @Test
    fun `source solving should refute impossible unsigned comparisons`() {
        val parsed = SmtLib.parse("(declare-const word (_ BitVec 3)) (assert (bvult word #b000))")
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val result = FinitePipeline.solve(FiniteSolveRequest(invocation.finiteShape(),
            SourceFiniteControls.Backtrack(BacktrackParams())), FiniteSolveCallbacks({}, {}))

        assertEquals(FiniteSolveVerdict.UNSAT, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
    }

    @Test
    fun `symbolic division and oversized shifts should preserve total source semantics`() {
        val parsed = SmtLib.parse("(declare-const x (_ BitVec 4)) (declare-const y (_ BitVec 4)) " +
            "(assert (= x #b1000)) (assert (= y #b0000)) " +
            "(assert (= (bvudiv x y) #b1111)) (assert (= (bvsdiv x y) #b0001)) " +
            "(assert (= (bvurem x y) x)) (assert (= (bvsrem x y) x)) (assert (= (bvsmod x y) x)) " +
            "(assert (= (bvshl x (bvnot y)) y)) (assert (= (bvlshr x (bvnot y)) y)) " +
            "(assert (= (bvashr x (bvnot y)) #b1111))")

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertEquals(listOf(SmtValue.BitVector("1000"), SmtValue.BitVector("0000")),
            parsed.reconstructModel(assignment).values.map { it.second })
    }

    @Test
    fun `generated conversion coordinates should ignore source integer endpoint overrides`() {
        val parsed = SmtLib.parse("(declare-const word (_ BitVec 4)) " +
            "(assert (= word #b1111)) (assert (= (ubv_to_int word) 15)) " +
            "(assert (= (sbv_to_int word) (- 1))) (assert (= ((_ int_to_bv 4) 255) word))",
            unboundedIntLo = 0, unboundedIntHi = 2)

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertEquals(SmtValue.BitVector("1111"), parsed.reconstructModel(assignment).values.single().second)
        assertTrue(parsed.intVarNames.isEmpty())
    }

    @Test
    fun `integer conversion should retain source coordinates past machine precision`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (= x 36893488147419103233)) " +
            "(assert (= ((_ int_to_bv 1) x) #b1))")
        var work = 0L
        val meter = WorkMeter { work += it }
        val cancellation = ExecutionControl(Cancellation { work >= 100_000 }, meter)

        val result = BacktrackSolver.source(parsed.model).solve(
            FiniteEngine.BACKTRACK, cancellation = cancellation,
        )
        val assignment = assertIs<OpenTheoryResult.Sat>(result,
            "work=$work route=${parsed.model.sourceRoute()} result=$result").assignment

        assertEquals(SmtValue.Integer("36893488147419103233"),
            parsed.reconstructModel(assignment).values.single().second)
    }

    @Test
    fun `quoted names should not capture bare literals during bitvector reduction`() {
        val sources = listOf(
            "(declare-const |0| Int) (assert (= |0| 1)) (assert (= |0| 0))",
            "(declare-const |0.0| Real) (assert (= |0.0| 1.0)) (assert (= |0.0| 0.0))",
            "(declare-const |#b0| (_ BitVec 1)) (assert (= |#b0| #b1)) (assert (= |#b0| #b0))",
            "(declare-const |#x0| (_ BitVec 4)) (assert (= |#x0| #x1)) (assert (= |#x0| #x0))",
        )

        for (source in sources) {
            val parsed = SmtLib.parse("(declare-const b (_ BitVec 1)) $source")

            assertIs<OpenTheoryResult.Unsat>(BacktrackSolver.source(parsed.model).solve(), source)
        }
    }

    @Test
    fun `qualified bitvector identifiers should retain their selected result sort`() {
        val parsed = SmtLib.parse("(declare-const b (_ BitVec 2)) (assert (= (as b (_ BitVec 2)) #b10)) " +
            "(assert (= ((as bvadd (_ BitVec 2)) #b01 #b01) b)) " +
            "(assert (= ((as (_ extract 0 0) (_ BitVec 1)) b) #b0))")

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertEquals(SmtValue.BitVector("10"), parsed.reconstructModel(assignment).values.single().second)
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(assert (= ((as bvadd (_ BitVec 1)) #b01 #b01) #b0))") }
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(assert (= (as (bvadd #b0 #b1) (_ BitVec 1)) #b1))") }
    }

    @Test
    fun `bitvector helpers should retain unused scalar argument domains`() {
        val bodies = listOf(
            "(define-fun f ((x Real)) Bool true) (assert (f (log 0)))",
            "(assert (let ((x (log 0))) true))",
            "(define-fun f ((x Real)) (_ BitVec 1) #b1) (assert (= (bvnot (f (log 0))) #b0))",
        )

        for (body in bodies) {
            val parsed = SmtLib.parse("(declare-const b (_ BitVec 1)) $body")
            val source = assertIs<TranscendentalProblem>(parsed.source.originalSource)

            assertIs<OpenTheoryResult.Unsat>(OpenTheoryPipeline.execute(source.pipelineRequest(),
                engine = FiniteEngine.BACKTRACK).result)
        }
    }

    @Test
    fun `inactive bitvector helper branches should retain local scalar domains`() {
        val parsed = SmtLib.parse("(define-fun f ((x Real)) (_ BitVec 1) #b0) " +
            "(assert (= (ite true #b1 (f (log 0))) #b1))")
        val source = assertIs<TranscendentalProblem>(parsed.source.originalSource)

        assertIs<OpenTheoryResult.Sat>(OpenTheoryPipeline.execute(source.pipelineRequest(),
            engine = FiniteEngine.BACKTRACK).result)
    }

    @Test
    fun `wide integer conversions should retain every source bit`() {
        val word = "#b1${"0".repeat(128)}"
        val parsed = SmtLib.parse("(assert (= (ubv_to_int $word) 340282366920938463463374607431768211456)) " +
            "(assert (= (sbv_to_int $word) (- 340282366920938463463374607431768211456)))")

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `malformed terms should fail even inside constant Boolean branches and unused helpers`() {
        val invalid = listOf(
            "(assert (or true (= (bvadd #b0 #b00) #b0)))",
            "(define-fun bad () (_ BitVec 1) (bvand #b0 #b00))",
            "(assert (= ((_ extract 4 0) #b0000) #b00000))",
            "(assert (= ((_ repeat 0) #b0) #b0))",
            "(assert (= ((_ zero_extend 2147483647) #b0) #b0))",
            "(assert (= ((_ rotate_left -1) #b0) #b0))",
            "(assert (= ((_ int_to_bv 4) 1.5) #b0001))",
            "(assert (= (_ bv16 4) #b0000))",
            "(assert (= #b2 #b0))",
            "(declare-const bad (_ BitVec 0))",
        )

        for (source in invalid) assertFailsWith<UnsupportedSmtException>(source) { SmtLib.parse(source) }
    }

    @Test
    fun `resource excessive widths should fail before allocating bit coordinates`() {
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(declare-const word (_ BitVec 1000001))") }
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(assert (= (_ bv1 2147483647) (_ bv1 2147483647)))") }
        assertFailsWith<UnsupportedSmtException> { SmtLib.parse("(assert (= (ubv_to_int (_ bv1 100000)) 1))") }
    }

    @Test
    fun `constant circuit construction should honor the containing cancellation token`() {
        var polls = 0
        val cancellation = ExecutionControl(Cancellation { ++polls > 1000 })

        assertFailsWith<CancellationException> {
            SmtLib.parse("(assert (= (bvmul #b${"0".repeat(256)} #b${"0".repeat(256)}) (_ bv0 256)))",
                cancellation = cancellation)
        }
    }
}
