package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.requireVariableCounts
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.PolynomialConditional
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

internal class SmtLibPolynomialProblem(
    val formula: PolynomialRealFormula,
    val realCount: Int,
    val boolCount: Int,
    val sourceRealCount: Int,
    val sourceBoolCount: Int,
    val hasNonlinearTerms: Boolean,
    intVarNames: Map<String, Int> = emptyMap(),
    realVarNames: Map<String, Int>,
    boolVarNames: Map<String, Int>,
    checkpoint: () -> Unit = {},
    val objective: QuadraticObjectiveSpec? = null,
    val objectiveSense: ObjectiveSense = ObjectiveSense.MINIMIZE,
) {
    init {
        require(sourceRealCount in 0..realCount && sourceBoolCount in 0..boolCount) {
            "invalid polynomial source count"
        }
        require((realVarNames.values + intVarNames.values).toSet() == (0 until sourceRealCount).toSet()) {
            "invalid polynomial real source map"
        }
        require(boolVarNames.values.toSet() == (0 until sourceBoolCount).toSet()) {
            "invalid polynomial Boolean source map"
        }
        formula.requireVariableCounts(boolCount, realCount, checkpoint)
    }

    private val integers = intVarNames.toMap()
    val intVarNames: Map<String, Int> get() = integers.toMap()
    private val reals = realVarNames.toMap()
    private val bools = boolVarNames.toMap()
    val realVarNames: Map<String, Int> get() = reals.toMap()
    val boolVarNames: Map<String, Int> get() = bools.toMap()
}

internal object SmtLibPolynomial {
    fun parse(
        text: String,
        cancellation: Cancellation = Cancellation.Never,
        allowIntegers: Boolean = false,
    ): SmtLibPolynomialProblem =
        parse(StringCharSource(text), cancellation, allowIntegers)

    fun parse(
        source: CharSource,
        cancellation: Cancellation = Cancellation.Never,
        allowIntegers: Boolean = false,
    ): SmtLibPolynomialProblem {
        cancellation.polynomialSourceCheckpoint()
        val reader = SExprReader(PolynomialSourceInput(source, cancellation))
        val compiler = SmtLibPolynomialCompiler(cancellation, allowIntegers)
        while (true) {
            cancellation.polynomialSourceCheckpoint()
            compiler.command(reader.readCommandOrNull() ?: break)
        }
        return compiler.build()
    }
}

internal class SmtLibPolynomialCompiler(
    private val cancellation: Cancellation = Cancellation.Never,
    private val allowIntegers: Boolean = false,
) {
    private val bindings = PolynomialBindings()
    private var environment = bindings.environment()
    private val intNames = LinkedHashMap<String, Int>()
    private val numericCount: Int get() = realNames.size + intNames.size
    private val realNames = LinkedHashMap<String, Int>()
    private val boolNames = LinkedHashMap<String, Int>()
    private val assertions = ArrayList<Pair<SExpr, PolynomialEnvironment>>()
    private var querySeen = false
    private var exited = false
    private var objective: Pair<SExpr, PolynomialEnvironment>? = null
    private var objectiveSense = ObjectiveSense.MINIMIZE

    fun command(expression: SExpr) {
        cancellation.polynomialSourceCheckpoint()
        val command = expression as? SExpr.SList ?: smtUnsupported("expected a parenthesized polynomial command")
        if (exited) smtUnsupported("polynomial command after exit")
        val head = command.polynomialHead()
        if (querySeen && head !in setOf("get-model", "get-objectives", "exit", "set-info", "set-option")) {
            smtUnsupported("multiple or incremental polynomial queries are unsupported")
        }
        when (head) {
            "declare-const" -> {
                command.polynomialSize(3)
                declare(command.polynomialName(1), command.polynomialAtom(2))
            }
            "declare-fun" -> {
                command.polynomialSize(4)
                val parameters = command.items[2] as? SExpr.SList ?: smtUnsupported("malformed declare-fun arguments")
                if (parameters.items.isNotEmpty()) {
                    smtUnsupported("polynomial arithmetic does not support uninterpreted functions")
                }
                declare(command.polynomialName(1), command.polynomialAtom(3))
            }
            "define-fun" -> define(command)
            "assert" -> {
                command.polynomialSize(2)
                assertions.add(command.items[1] to environment)
            }
            "minimize", "maximize" -> {
                command.polynomialSize(2)
                if (objective != null) smtUnsupported("multiple polynomial objectives are unsupported")
                objective = command.items[1] to environment
                objectiveSense = if (head == "minimize") ObjectiveSense.MINIMIZE else ObjectiveSense.MAXIMIZE
            }
            "set-logic" -> {
                command.polynomialSize(2)
                if (command.polynomialAtom(1) !in setOf("QF_NRA", "QF_LRA", "QF_RDL", "ALL") &&
                    !(allowIntegers && command.polynomialAtom(1) in setOf("QF_NIA", "QF_NIRA", "QF_LIA", "QF_LIRA"))
                ) {
                    smtUnsupported("unsupported polynomial logic '${command.polynomialAtom(1)}'")
                }
            }
            "set-info", "set-option" -> command.polynomialSize(3)
            "check-sat" -> {
                command.polynomialSize(1)
                querySeen = true
            }
            "check-sat-assuming" -> {
                command.polynomialSize(2)
                val assumptions = command.items[1] as? SExpr.SList ?: smtUnsupported("malformed polynomial assumptions")
                for (assumption in assumptions.items) {
                    val atom = when (assumption) {
                        is SExpr.Atom -> assumption
                        is SExpr.SList -> {
                            assumption.polynomialSize(2)
                            if (assumption.polynomialHead() != "not") {
                                smtUnsupported("polynomial assumption must be a Boolean literal")
                            }
                            assumption.items[1] as? SExpr.Atom
                                ?: smtUnsupported("polynomial assumption must be a Boolean literal")
                        }
                    }
                    if (environment.value(polynomialSymbol(atom.text)) !is PolynomialValue.Boolean) {
                        smtUnsupported("polynomial assumption must name a declared Boolean")
                    }
                    assertions.add(assumption to environment)
                }
                querySeen = true
            }
            "get-model" -> command.polynomialSize(1)
            "get-objectives" -> {
                command.polynomialSize(1)
                if (objective == null) smtUnsupported("get-objectives requires an objective")
            }
            "exit" -> {
                command.polynomialSize(1)
                exited = true
            }
            else -> smtUnsupported("unsupported polynomial command '$head'")
        }
    }

    fun build(): SmtLibPolynomialProblem {
        val conditionals = ArrayList<PolynomialConditional>()
        val captureConditional: ((PolynomialConditional) -> Unit)? = if (objective == null) null else {
            conditional -> conditionals.add(conditional)
        }
        val lowering = PolynomialRealLowering(numericCount, captureConditional)
        val evaluator = SmtLibPolynomialEvaluator(
            lowering, allowWidePowers = allowIntegers, cancellation = cancellation,
        )
        val formulas = assertions.map { (expression, environment) ->
            evaluator.evaluate(expression, environment).boolean()
        }
        val captured = objective?.let { (expression, environment) ->
            val value = evaluator.evaluate(expression, environment) as? PolynomialValue.Real
                ?: smtUnsupported("polynomial objective must be numeric")
            SmtLibQuadraticObjective.capture(
                value.expression, intNames, realNames, boolNames.size, lowering.realCount, conditionals, cancellation,
            )
        }
        return SmtLibPolynomialProblem(
            lowering.formula(formulas), lowering.realCount, boolNames.size, numericCount, boolNames.size,
            evaluator.hasNonlinearTerms, intNames, realNames, boolNames, { cancellation.polynomialSourceCheckpoint() },
            captured, objectiveSense,
        )
    }

    private fun declare(name: String, sort: String) {
        unusedName(name)
        val value = when (sort) {
            "Real" -> PolynomialValue.Real.variable(numericCount).also { realNames[name] = numericCount }
            "Int" -> {
                if (!allowIntegers) smtUnsupported("unsupported polynomial sort 'Int'")
                PolynomialValue.Real.variable(numericCount, integer = true).also { intNames[name] = numericCount }
            }
            "Bool" -> PolynomialValue.Boolean.variable(boolNames.size).also { boolNames[name] = boolNames.size }
            else -> smtUnsupported("unsupported polynomial sort '$sort'")
        }
        bindings.declare(name, value)
        environment = bindings.environment()
    }

    private fun define(command: SExpr.SList) {
        command.polynomialSize(5)
        val name = command.polynomialName(1)
        unusedName(name)
        val parameters = command.items[2] as? SExpr.SList ?: smtUnsupported("malformed define-fun parameters")
        val names = ArrayList<String>()
        val sorts = ArrayList<Sort>()
        for (parameter in parameters.items) {
            val pair = parameter as? SExpr.SList ?: smtUnsupported("malformed polynomial parameter")
            if (pair.items.size != 2) smtUnsupported("malformed polynomial parameter")
            val parameterName = pair.polynomialName(0)
            if (parameterName in names) smtUnsupported("duplicate polynomial parameter '$parameterName'")
            names.add(parameterName)
            val sort = polynomialSort(pair.polynomialAtom(1))
            if (sort == Sort.INT && !allowIntegers) smtUnsupported("unsupported polynomial sort 'Int'")
            sorts.add(sort)
        }
        val resultSort = polynomialSort(command.polynomialAtom(3))
        if (resultSort == Sort.INT && !allowIntegers) smtUnsupported("unsupported polynomial sort 'Int'")
        val macro = PolynomialMacro(
            names, sorts, resultSort, command.items[4],
            environment,
        )
        val parametersForValidation = names.mapIndexed { index, parameter ->
            parameter to if (sorts[index] == Sort.BOOL) PolynomialValue.Boolean.variable(index)
            else PolynomialValue.Real.variable(index, integer = sorts[index] == Sort.INT)
        }.toMap()
        val value = SmtLibPolynomialEvaluator(
            PolynomialRealLowering(numericCount + names.size), validateOnly = true,
            allowWidePowers = allowIntegers, cancellation = cancellation,
        ).evaluate(
            macro.body, macro.environment.withValues(parametersForValidation),
        )
        if (macro.resultSort == Sort.BOOL) value.boolean() else if (value !is PolynomialValue.Real ||
            (macro.resultSort == Sort.INT && !value.integer)
        ) {
            smtUnsupported("polynomial macro result must be Real")
        }
        bindings.define(name, macro)
        environment = bindings.environment()
    }

    private fun unusedName(name: String) {
        if (bindings.contains(name) || name in setOf("true", "false")) {
            smtUnsupported("duplicate or reserved polynomial declaration '$name'")
        }
    }
}

internal fun polynomialSort(sort: String): Sort = when (sort) {
    "Bool" -> Sort.BOOL
    "Real" -> Sort.REAL
    "Int" -> Sort.INT
    else -> smtUnsupported("unsupported polynomial sort '$sort'")
}

internal fun SExpr.SList.polynomialAtom(index: Int): String =
    (items.getOrNull(index) as? SExpr.Atom)?.text?.let(::polynomialSymbol)
        ?: smtUnsupported("malformed polynomial atom")

internal fun SExpr.SList.polynomialHead(): String = polynomialAtom(0)

internal fun SExpr.SList.polynomialSize(expected: Int) {
    if (items.size != expected) smtUnsupported("${polynomialHead()} expects ${expected - 1} arguments")
}

internal fun polynomialSymbol(text: String): String =
    if (text.startsWith('|') && text.endsWith('|')) text.substring(1, text.length - 1) else text

internal fun SExpr.SList.polynomialName(index: Int): String {
    val text = (items.getOrNull(index) as? SExpr.Atom)?.text ?: smtUnsupported("malformed polynomial symbol")
    if (text.startsWith('|') && text.endsWith('|')) return polynomialSymbol(text)
    val punctuation = "~!@$%^&*_+=<>.?/-"
    fun initial(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z' || character in punctuation
    if (text.isEmpty() || !initial(text[0]) || !text.all { initial(it) || it in '0'..'9' }) {
        smtUnsupported("invalid polynomial symbol '$text'")
    }
    return text
}

private class PolynomialSourceInput(
    private val source: CharSource,
    private val cancellation: Cancellation,
) : CharSource {
    private var chunk = ""
    private var offset = 0

    override fun next(): String? {
        cancellation.polynomialSourceCheckpoint()
        if (offset == chunk.length) {
            chunk = source.next() ?: return null
            offset = 0
        }
        val end = minOf(chunk.length.toLong(), offset.toLong() + 4096).toInt()
        return chunk.substring(offset, end).also { offset = end }
    }
}
