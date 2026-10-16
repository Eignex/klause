package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceDefinitionProjectionTest {
    @Test
    fun `a native objective view should be built from a plain source program`() {
        val source = "var -3..3: x; var 0..3: a; " +
            "constraint int_abs(x, a) :: defines_var(a); solve minimize a;"
        val program = parseFlatZinc(source)
        val sweep = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))
        val objective = assertNotNull(sweep.functionalObjective(assertNotNull(program.linearObjective())))
        val assignment = Assignment(program.problem.numBoolVars, program.problem.numIntVars)
        val x = program.intVarsByName.getValue("x")
        assignment.setInt(x, -3)

        val delta = objective.deltaIfApplied(assignment, Move.IntSet(x, 1))
        assignment.setInt(x, 1)

        assertEquals(-2.0, delta)
        assertEquals(1.0, objective.evaluate(assignment))
    }

    @Test
    fun `wide intermediate sums should preserve a representable linear definition`() {
        val source = "var 2..2: x; var ${Long.MAX_VALUE}..${Long.MAX_VALUE}: result; " +
            "constraint int_lin_eq([2, -${Long.MAX_VALUE}], [result, x], 0) :: defines_var(result); " +
            "solve minimize result;"
        val program = parseFlatZinc(source)
        val sweep = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))
        val canonical = assertNotNull(program.linearObjective())
        val objective = assertNotNull(sweep.functionalObjective(canonical))
        val assignment = Assignment(program.problem.numBoolVars, program.problem.numIntVars)
        assignment.setInt(program.intVarsByName.getValue("x"), 2L)
        assignment.setInt(program.intVarsByName.getValue("result"), Long.MAX_VALUE)

        assertEquals(canonical.evaluate(assignment), objective.evaluate(assignment))
    }

    @Test
    fun `strict endpoint comparisons should not wrap their source bound`() {
        for ((endpoint, relation) in listOf(Long.MIN_VALUE to LinearOp.LE, Long.MAX_VALUE to LinearOp.GE)) {
            val problem = Problem(
                numBoolVars = 1, numIntVars = 1, intDomains = arrayOf(IntDomain.range(endpoint, endpoint)),
                factors = arrayOf(Clause(intArrayOf(Lit.make(0, false)))),
            )
            val hint = DefinitionHint.Comparison(
                0, longArrayOf(1, -1), arrayOf(
                    DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Constant(endpoint),
                ), 0, relation, strict = true,
            )
            val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(hint)))
            val objective = assertNotNull(sweep.functionalObjective(LinearObjective(boolWeights = longArrayOf(1))))
            val assignment = Assignment(1, 1)
            assignment.setInt(0, endpoint)
            assignment.setBool(0, false)

            assertEquals(0.0, objective.evaluate(assignment))
        }
    }

    @Test
    fun `membership objective gradients should follow the source integer coordinate`() {
        val source = "var 0..2: x; var bool: b; " +
            "constraint set_in_reif(x, {0, 2}, b) :: defines_var(b); solve minimize b;"
        val program = parseFlatZinc(source)
        val sweep = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))
        val canonical = assertNotNull(program.linearObjective())
        val objective = assertNotNull(sweep.functionalObjective(canonical))
        val assignment = Assignment(program.problem.numBoolVars, program.problem.numIntVars)
        val x = program.intVarsByName.getValue("x")
        assignment.setInt(x, 1)
        assignment.setBool(program.boolVarsByName.getValue("b"), false)

        val delta = objective.deltaIfApplied(assignment, Move.IntSet(x, 2))
        assignment.setInt(x, 2)
        assignment.setBool(program.boolVarsByName.getValue("b"), true)

        assertEquals(1.0, delta)
        assertEquals(canonical.evaluate(assignment), objective.evaluate(assignment))
    }

    @Test
    fun `Boolean literal polarity should survive native objective materialization`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain.range(0, 1)),
            arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)),
        )
        val hint = DefinitionHint.BooleanValue(0, Lit.make(0, false))
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(hint)))
        val canonical = LinearObjective(intCoefficients = longArrayOf(1))
        val objective = assertNotNull(sweep.functionalObjective(canonical))
        val assignment = Assignment(1, 1)
        assignment.setBool(0, true)
        assignment.setInt(0, 0)

        val delta = objective.deltaIfApplied(assignment, Move.BoolFlip(0))
        assignment.flipBool(0)
        assignment.setInt(0, 1)

        assertEquals(1.0, delta)
        assertEquals(canonical.evaluate(assignment), objective.evaluate(assignment))
    }

    @Test
    fun `competing definition hints should leave their output searched`() {
        val source = "var -2..2: x; var 0..2: y; var 0..2: result; " +
            "constraint int_abs(x, result) :: defines_var(result); " +
            "constraint int_plus(y, 0, result) :: defines_var(result); solve minimize result;"
        val program = parseFlatZinc(source)

        val sweep = DefinitionalSweep.fromSource(program.problem, program.definitionHints)

        assertNull(sweep)
    }

    @Test
    fun `cyclic definition hints should leave their outputs searched`() {
        val source = "var 0..2: x; var 1..3: y; " +
            "constraint int_plus(x, 1, y) :: defines_var(y); " +
            "constraint int_plus(y, -1, x) :: defines_var(x); solve minimize y;"
        val program = parseFlatZinc(source)

        val sweep = DefinitionalSweep.fromSource(program.problem, program.definitionHints)

        assertNull(sweep)
    }

    @Test
    fun `a cancelled materializer should not publish a partial sweep`() {
        val program = parseFlatZinc(
            "var -2..2: x; var 0..2: a; constraint int_abs(x, a) :: defines_var(a); solve satisfy;",
        )
        var polls = 0

        val cancelled = DefinitionalSweep.fromSource(
            program.problem, program.definitionHints, Cancellation { ++polls >= 2 },
        )
        val retry = DefinitionalSweep.fromSource(program.problem, program.definitionHints)

        assertNull(cancelled)
        assertNotNull(retry)
    }

    @Test
    fun `a sentinel-valued output should remain a source search coordinate`() {
        val source = "var ${Long.MIN_VALUE}..${Long.MIN_VALUE}: x; " +
            "var ${Long.MIN_VALUE}..${Long.MAX_VALUE}: y; " +
            "constraint int_plus(x, 0, y) :: defines_var(y); solve minimize y;"
        val program = parseFlatZinc(source)

        val sweep = DefinitionalSweep.fromSource(program.problem, program.definitionHints)

        assertNull(sweep)
    }

    @Test
    fun `reverse ordered source definitions should evaluate in dependency order`() {
        val count = 1024
        val domain = IntDomain.range(0, count.toLong())
        val factors = Array<Factor>(count) { k ->
            Linear(intArrayOf(1, -1), intArrayOf(k + 1, k), LinearOp.EQ, 1)
        }
        val problem = Problem(0, count + 1, Array(count + 1) { domain }, factors)
        val hints = List(count) { k ->
            val output = count - k
            DefinitionHint.Plus(
                output, DefinitionHint.Operand.Variable(output - 1), DefinitionHint.Operand.Constant(1),
            )
        }
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, hints))
        val canonical = LinearObjective(intCoefficients = LongArray(count + 1).also { it[count] = 1L })
        val objective = assertNotNull(sweep.functionalObjective(canonical))
        val assignment = Assignment(0, count + 1)
        for (k in 0..count) assignment.setInt(k, k.toLong())

        assertEquals(canonical.evaluate(assignment), objective.evaluate(assignment))
    }
}
