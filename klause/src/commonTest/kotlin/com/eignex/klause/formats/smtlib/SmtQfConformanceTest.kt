package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.toExactRealValue
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceFiniteControls
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtQfConformanceTest {
    @Test
    fun `function bitvector results address reconstructed arrays`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const u U)
            (declare-fun address (U) (_ BitVec 2))
            (declare-const a (Array (_ BitVec 2) Bool))
            (assert (= (address u) #b10))
            (assert (select a (address u)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val values = parsed.reconstructModel(assignment).values.associate { it.first.name to it.second }

        val address = assertIs<SmtValue.Function>(values.getValue("address"))
        val index = address.entries.lastOrNull { it.first == listOf(values.getValue("u")) }?.second ?: address.default
        assertEquals(SmtValue.BitVector("10"), index)
        val array = assertIs<SmtValue.Array>(values.getValue("a"))
        assertEquals(SmtValue.Boolean(true), array.entries.lastOrNull { it.first == index }?.second ?: array.default)
        assertEquals(setOf("u", "address", "a"), values.keys)
        assertTrue(parsed.intVarNames.isEmpty())
    }

    @Test
    fun `array stores use interpreted bitvector equality for function indexes`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const u U)
            (declare-fun address (U) (_ BitVec 2))
            (declare-const a (Array (_ BitVec 2) Bool))
            (assert (= (address u) (bvadd #b01 #b01)))
            (assert (not (select (store a #b10 true) (address u))))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `datatype function results retain bitvector and exact real array fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Packet ((packet (bits (_ BitVec 1)) (weights (Array Bool Real)))))
            (declare-const w (Array Bool Real))
            (declare-fun payload (Bool) Packet)
            (assert (= (payload true) (packet #b1 w)))
            (assert (= (select w true) (/ 1.0 3.0)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val values = parsed.reconstructModel(assignment).values.associate { it.first.name to it.second }

        val payload = assertIs<SmtValue.Function>(values.getValue("payload"))
        val packet = assertIs<SmtValue.Application>(payload.entries.lastOrNull {
            it.first == listOf(SmtValue.Boolean(true))
        }?.second ?: payload.default)
        assertEquals(SmtValue.BitVector("1"), packet.arguments[0])
        val weights = assertIs<SmtValue.Array>(packet.arguments[1])
        val weight = weights.entries.lastOrNull { it.first == SmtValue.Boolean(true) }?.second ?: weights.default
        assertEquals("(/ 1.0 3.0)", assertIs<SmtValue.Real>(weight).term)
        assertEquals(setOf("w", "payload"), values.keys)
    }

    @Test
    fun `datatype array indexes preserve function congruence`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Key ((first) (second)))
            (declare-fun key (Bool) Key)
            (declare-const a (Array Key (_ BitVec 2)))
            (assert (= (key true) second))
            (assert (distinct (select (store a second #b11) (key true)) #b11))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `nested array models contain datatype values at bitvector indexes`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Color ((red) (green)))
            (declare-const a (Array (_ BitVec 1) (Array Bool Color)))
            (assert (= (select (select a #b1) true) green))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val outer = assertIs<SmtValue.Array>(parsed.reconstructModel(assignment).values.single().second)

        val inner = assertIs<SmtValue.Array>(outer.entries.lastOrNull {
            it.first == SmtValue.BitVector("1")
        }?.second ?: outer.default)
        val color = inner.entries.lastOrNull { it.first == SmtValue.Boolean(true) }?.second ?: inner.default
        assertEquals("green", assertIs<SmtValue.Application>(color).constructor)
    }

    @Test
    fun `finite datatype cardinality includes bitvector fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (bit (_ BitVec 1)))))
            (declare-const a Box)
            (declare-const b Box)
            (declare-const c Box)
            (assert (distinct a b c))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `variable euclidean quotient converts into an array bitvector index`() {
        val parsed = SmtLib.parse("""
            (declare-fun numerator (Bool) Int)
            (declare-const divisor Int)
            (declare-const index (_ BitVec 2))
            (declare-const a (Array (_ BitVec 2) Bool))
            (assert (= (numerator true) (- 7)))
            (assert (= divisor 3))
            (assert (= index ((_ int_to_bv 2) (div (numerator true) divisor))))
            (assert (select (store a #b01 true) index))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val values = parsed.reconstructModel(assignment).values.associate { it.first.name to it.second }

        assertEquals(SmtValue.BitVector("01"), values.getValue("index"))
        assertEquals(SmtValue.Integer("3"), values.getValue("divisor"))
    }

    @Test
    fun `exact rational array indexes agree through uninterpreted functions`() {
        val parsed = SmtLib.parse("""
            (declare-fun address (Real) Real)
            (declare-const a (Array Real Bool))
            (assert (select a (address (/ 1.0 3.0))))
            (assert (not (select a (address (/ 2.0 6.0)))))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `polymorphic distinct constants respect the boolean instance`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-const b A)
            (declare-const c A)
            (assert (distinct a b c))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `quoted source names survive composite model reconstruction`() {
        val parsed = SmtLib.parse("""
            (declare-sort |Source sort| 0)
            (declare-const |source value| |Source sort|)
            (declare-fun |source function| (|Source sort|) (_ BitVec 1))
            (assert (= (|source function| |source value|) #b1))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals(listOf("|source value|", "|source function|"), model.values.map { it.first.name })
        val function = assertIs<SmtValue.Function>(model.values[1].second)
        val argument = model.values[0].second
        val result = function.entries.lastOrNull { it.first == listOf(argument) }?.second ?: function.default
        assertEquals(SmtValue.BitVector("1"), result)
    }

    @Test
    fun `indexed divisibility composes with datatype bitvector array values`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Tag ((tag (bit (_ BitVec 1)))))
            (declare-const a (Array Bool Tag))
            (assert (= (select a true) (tag #b1)))
            (assert ((_ divisible 18446744073709551618) 36893488147419103236))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val array = assertIs<SmtValue.Array>(parsed.reconstructModel(assignment).values.single().second)

        val tag = assertIs<SmtValue.Application>(array.entries.lastOrNull {
            it.first == SmtValue.Boolean(true)
        }?.second ?: array.default)
        assertEquals(listOf(SmtValue.BitVector("1")), tag.arguments)
    }

    @Test
    fun `mixed numeric conditionals retain real array index sorts`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Unit ((unit)))
            (declare-const a (Array Real Bool))
            (declare-const c Bool)
            (declare-const bit (_ BitVec 1))
            (assert (not c))
            (assert (= bit #b1))
            (assert (select (store a 0.5 true) (ite c 0 0.5)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `qualified indexed operators retain their declared result sorts`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Tag ((tag (bit (_ BitVec 1)))))
            (assert ((as (_ is tag) Bool) (tag #b1)))
            (assert (= ((as (_ extract 0 0) (_ BitVec 1)) #b11) #b1))
            (assert ((as (_ divisible 2) Bool) 4))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `quoted builtin identities never turn quoted numeral names into literals`() {
        val parsed = SmtLib.parse("""
            (declare-const |1| (_ |BitVec| 1))
            (declare-const a (Array Bool (_ BitVec 1)))
            (assert |true|)
            (assert (|=| |1| #b1))
            (assert (= ((as |select| (_ BitVec 1)) (|store| a |true| |1|) |true|) #b1))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals(SmtValue.BitVector("1"), model.values.single { it.first.name == "|1|" }.second)
    }

    @Test
    fun `quoted grammar word helpers retain lexical function meaning`() {
        val parsed = SmtLib.parse("""
            (define-fun |let| ((x (_ BitVec 1))) (_ BitVec 1) (bvnot x))
            (declare-const a (Array Bool (_ BitVec 1)))
            (assert (= (select (store a true (|let| #b1)) true) #b0))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `function model formals cannot shadow source constructors`() {
        val constructor = "(declare-datatype Value ((x0 (field Bool))))"
        val parsed = SmtLib.parse("$constructor (declare-fun f (Bool) Value)")
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val rendered = parsed.reconstructModel(assignment).toSmtLib()

        val commands = SExprReader(constructor + rendered.removePrefix("(").removeSuffix(")")).readAll()
        val scope = SmtLibScope(ExecutionControl.Never, allowSolverSymbols = true)

        assertTrue(commands.all(scope::command))
    }

    @Test
    fun `retained polymorphic models instantiate new array and wide word ranks coherently`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-fun f (A) A)
            (assert (= (f a) a))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)

        val word = model.evaluate(SExprReader("(as a (_ BitVec 129))").readAll().single())
        val wordEquation = model.evaluate(SExprReader(
            "(= ((as f (_ BitVec 129)) (as a (_ BitVec 129))) (as a (_ BitVec 129)))").readAll().single())
        val arrayEquation = model.evaluate(SExprReader(
            "(= ((as f (Array Bool Bool)) (as a (Array Bool Bool))) (as a (Array Bool Bool)))").readAll().single())

        assertEquals(131, word.length)
        assertTrue(word.startsWith("#b"))
        assertEquals("true", wordEquation)
        assertEquals("true", arrayEquation)
        assertEquals(word, model.evaluate(SExprReader("(as a (_ BitVec 129))").readAll().single()))
    }

    @Test
    fun `native exact array keys compare across different defining polynomials`() {
        val roots = AlgebraicArithmetic()
        val first = roots.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = roots.roots(RationalPolynomial.of(-4, 0, 0, 0, 1)).last()
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values ->
                val sort = SmtSort.Array(SmtSort.Real, SmtSort.Bool)
                val left = SmtValue.Array(sort, SmtValue.Boolean(false),
                    listOf(values.value("a") to SmtValue.Boolean(true)))
                val right = SmtValue.Array(sort, SmtValue.Boolean(false),
                    listOf(values.value("b") to SmtValue.Boolean(true)))
                values.equal(left, right) == true
            }),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0, 0.0),
            algebraicReals = listOf(first, second))

        val model = parsed.reconstructModel(sample)

        assertTrue((model.values[0].second as SmtValue.Real).term != (model.values[1].second as SmtValue.Real).term)
    }

    @Test
    fun `retained division queries preserve chosen total interpretations`() {
        val parsed = SmtLib.parse("""
            (assert (= (div 3 0) 7))
            (assert (= (mod 3 0) (- 2)))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)

        val quotient = model.evaluate(SExprReader("(div 3 0)").readAll().single())
        val remainder = model.evaluate(SExprReader("(mod 3 0)").readAll().single())
        val unseen = model.evaluate(SExprReader("(div 4 0)").readAll().single())

        assertEquals("7", quotient)
        assertEquals("(- 2)", remainder)
        assertEquals(unseen, model.evaluate(SExprReader("(div (+ 2 2) 0)").readAll().single()))
    }

    @Test
    fun `retained datatype selectors preserve observed and unobserved result sorts`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Option (par (T) ((none) (some (value T)))))
            (assert (= (value (as none (Option Int))) 17))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)

        val observed = model.evaluate(SExprReader("(value (as none (Option Int)))").readAll().single())
        val constructorField = model.evaluate(SExprReader("(value (some 99))").readAll().single())
        val unseen = model.evaluate(SExprReader("(value (as none (Option Bool)))").readAll().single())

        assertEquals("17", observed)
        assertEquals("99", constructorField)
        assertTrue(unseen in setOf("true", "false"))
        assertEquals(unseen, model.evaluate(SExprReader("(value (as none (Option Bool)))").readAll().single()))
    }

    @Test
    fun `direct library named terms remain visible to retained model queries`() {
        val parsed = SmtLib.parse("(assert (! true :named label))")
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)

        val label = model.evaluate(SExpr.Atom("label"))

        assertEquals("true", label)
    }

    @Test
    fun `rejected composite source checks withhold finite publications`() {
        val original = SmtLib.parse("""
            (declare-const a (Array Bool (_ BitVec 1)))
            (assert (= (select a true) #b1))
        """.trimIndent())
        var checkedComposite = false
        val parsed = original.copy(sourceChecks = original.sourceChecks + SmtSourceCheck { values ->
            val array = values.value("a") as SmtValue.Array
            val selected = array.entries.lastOrNull { it.first == SmtValue.Boolean(true) }?.second ?: array.default
            checkedComposite = selected == SmtValue.BitVector("1")
            selected == SmtValue.BitVector("0")
        }).withSourceWitnessValidation()
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        var publications = 0

        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
            presolveConfig = PresolveConfig.NONE),
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ publications++ }, { publications++ })))

        val finite = assertIs<SourceExecutionResult.Finite>(result)
        val completed = assertIs<FiniteSolveOutcome.Completed>(finite.result.outcome)
        assertTrue(checkedComposite)
        assertEquals(FiniteSolveVerdict.UNKNOWN, completed.verdict)
        assertNull(completed.bestSample)
        assertEquals(0, publications)
    }

    @Test
    fun `sessions preserve composite models across query only assumptions`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession(::solveSession, replies::add)

        session.execute(SExprReader("""
            (set-option :produce-models true)
            (declare-const a (Array Bool (_ BitVec 1)))
            (assert (= (select a true) #b1))
            (check-sat)
            (get-value ((select a true)))
            (check-sat-assuming ((distinct (select a true) #b1)))
            (check-sat)
            (get-value ((select a true)))
        """.trimIndent()))

        assertEquals(listOf("sat", "(((select a true) #b1))", "unsat", "sat", "(((select a true) #b1))"), replies)
    }

    @Test
    fun `retained queries accept their previously returned abstract domain values`() {
        val parsed = SmtLib.parse("(declare-sort U 0) (declare-const a U)")
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)
        val value = model.evaluate(SExpr.Atom("a"))

        val same = model.evaluate(SExprReader("(= a $value)").readAll().single())

        assertTrue(value.startsWith("(as @"))
        assertEquals("true", same)
    }

    @Test
    fun `retained source constants take precedence over analytic extension names`() {
        val parsed = SmtLib.parse("(declare-const pi Real) (assert (= pi 0.0))")
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)

        val value = model.evaluate(SExpr.Atom("pi"))

        assertEquals("0.0", value)
    }

    @Test
    fun `qualified numeric aliases retain real array index values`() {
        val parsed = SmtLib.parse("""
            (define-sort R () Real)
            (declare-const a (Array R Bool))
            (assert (select a ((as + R) 1 2)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val array = assertIs<SmtValue.Array>(parsed.reconstructModel(assignment).values.single().second)

        assertEquals(SmtSort.Real, array.sort.index)
        assertTrue(array.entries.all { it.first is SmtValue.Real })
        val selected = array.entries.lastOrNull { it.first == SmtValue.Real("3.0") }?.second ?: array.default
        assertEquals(SmtValue.Boolean(true), selected)
    }

    @Test
    fun `retained polymorphic families construct deep array ranks without recursion`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (assert (= a a))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val family = parsed.originalSource!!.polymorphicModel!!
        var sort: SmtSort = SmtSort.Bool
        repeat(4096) { sort = SmtSort.Array(SmtSort.Bool, sort) }

        var value = withSmtModelValues(parsed, assignment::boolValue, assignment::intValue,
            assignment::realValue, ExecutionControl.Never) { values, _ ->
            requireNotNull(family.instantiate("a", emptyList(), sort, values))
        }

        var depth = 0
        while (value is SmtValue.Array) {
            assertEquals(SmtSort.Bool, value.sort.index)
            assertTrue(value.entries.isEmpty())
            value = value.default
            depth++
        }
        assertEquals(4096, depth)
        assertIs<SmtValue.Boolean>(value)
    }

    @Test
    fun `retained deep rank construction observes its current witness allowance`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (assert (= a a))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val family = parsed.originalSource!!.polymorphicModel!!
        var sort: SmtSort = SmtSort.Bool
        repeat(4096) { sort = SmtSort.Array(SmtSort.Bool, sort) }
        var work = 0L
        val control = ExecutionControl(Cancellation { work >= 100 }, WorkMeter { work += it })

        assertFailsWith<CancellationException> {
            withSmtModelValues(parsed, assignment::boolValue, assignment::intValue,
                assignment::realValue, control) { values, _ ->
                family.instantiate("a", emptyList(), sort, values)
            }
        }
    }

    @Test
    fun `mutual recursive definitions retain total interpretations at new arguments`() {
        val source = """
            (define-funs-rec
                ((even ((n Int)) Bool) (odd ((n Int)) Bool))
                ((ite (<= n 0) true (odd (- n 1)))
                 (ite (<= n 0) false (even (- n 1)))))
            (assert (even 2))
            (check-sat)
        """.trimIndent()

        val result = solveSmtRecursiveSession(source, ::solveSession)

        assertEquals("sat", result.status)
        val model = requireNotNull(result.model)
        assertEquals("false", model.evaluate(SExprReader("(even 3)").readAll().single()))
        assertEquals("true", model.evaluate(SExprReader("(odd 3)").readAll().single()))
    }

    @Test
    fun `mutual recursive equations refute inconsistent ground applications`() {
        val source = """
            (define-funs-rec
                ((f ((n Int)) Int) (g ((n Int)) Int))
                ((g n) (f n)))
            (assert (distinct (f 0) (g 0)))
            (check-sat)
        """.trimIndent()

        val result = solveSmtRecursiveSession(source, ::solveSession)

        assertEquals("unsat", result.status)
    }

    @Test
    fun `assignment queries evaluate true and false named terms in the checked model`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession(::solveSession, replies::add)

        session.execute(SExprReader("""
            (set-option :produce-assignments true)
            (assert (or (! true :named truth) (! false :named falsehood)))
            (check-sat)
            (get-assignment)
        """.trimIndent()))

        assertEquals(2, replies.size)
        assertEquals("sat", replies[0])
        val assignment = SExprReader(replies[1]).readAll().single() as SExpr.SList
        assertEquals(listOf("(falsehood false)", "(truth true)"), assignment.items.map { it.smtText() }.sorted())
    }

    @Test
    fun `retained symbolic coordinates preserve native inverse identities`() {
        val parsed = SmtLib.parse("(declare-const x Real)")
        val exponential = smtExactArithmetic(ExecutionControl.Never)
            .function(ExactRealFunction.EXP, BigFraction.ONE.toExactRealValue())
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), symbolicReals = listOf(exponential))

        val model = parsed.reconstructSessionModel(sample)

        assertEquals("true", model.evaluate(SExprReader("(= (log x) 1.0)").readAll().single()))
        assertEquals("false", model.evaluate(SExprReader("(= x 0.0)").readAll().single()))
    }

    @Test
    fun `symbolic query syntax cannot replace unavailable native authority`() {
        val source = SExprReader("(declare-const x Real)").readAll()
        val exponential = smtExactArithmetic(ExecutionControl.Never)
            .function(ExactRealFunction.EXP, BigFraction.ONE.toExactRealValue())
        val value = SmtValue.Real(smtSymbolicTerm(exponential))
        val declaration = SmtSourceDeclaration("x", emptyList(), SmtSort.Real)
        val values = object : SmtModelValues {
            override fun checkpoint(units: Long) = Unit
            override fun boolean(name: String): Boolean = error(name)
            override fun integer(name: String): String = error(name)
            override fun real(name: String): String = value.term
            override fun value(name: String): SmtValue = value
            override fun exactReal(value: SmtValue.Real): ExactRealValue? = null
        }
        val model = SmtFixedModel(source, SmtSourceModel(listOf(declaration to value)), values, ExecutionControl.Never)

        assertFailsWith<UnsupportedSmtException> {
            model.evaluate(SExprReader("(= (log x) 1.0)").readAll().single())
        }
    }

    @Test
    fun `retained real division queries preserve their chosen total interpretation`() {
        val parsed = SmtLib.parse("(assert (= (/ 1.0 0.0) 0.5))")
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructSessionModel(assignment)

        val value = model.evaluate(SExprReader("(/ 1.0 0.0)").readAll().single())

        assertEquals("(/ 1.0 2.0)", value)
    }

    private fun solveSession(text: String): SmtSessionResult {
        val parsed = SmtLib.parse(text)
        return when (val result = solve(parsed)) {
            is OpenTheoryResult.Sat -> SmtSessionResult("sat", parsed.reconstructSessionModel(result.assignment))
            is OpenTheoryResult.Unsat -> SmtSessionResult("unsat")
            is OpenTheoryResult.Unknown -> error("inconclusive session conformance solve: ${result.reason}")
        }
    }

    private fun solve(parsed: SmtLibProblem): OpenTheoryResult {
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        var sample: Sample? = null
        return when (val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
            presolveConfig = PresolveConfig.NONE),
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ sample = it }, {})))) {
            is SourceExecutionResult.Polynomial -> result.result.result
            is SourceExecutionResult.Transcendental -> result.result.result
            is SourceExecutionResult.Linear -> assertIs<OpenTheoryExecution.Satisfy>(result.result).result
            is SourceExecutionResult.Finite -> {
                val completed = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
                when (completed.verdict) {
                    FiniteSolveVerdict.SAT -> OpenTheoryResult.Sat(
                        OpenTheoryAssignment.Sampled(requireNotNull(completed.bestSample ?: sample)), completed.stats)
                    FiniteSolveVerdict.UNSAT -> OpenTheoryResult.Unsat(completed.stats)
                    else -> error("inconclusive conformance solve: ${completed.verdict}")
                }
            }
            is SourceExecutionResult.Refuted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
            else -> error("unexpected conformance execution: $result")
        }
    }
}
