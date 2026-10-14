package com.eignex.klause.theory

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.LinearEncodingInterrupted
import com.eignex.klause.lowering.LinearFormulaLowering
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation

internal class EncodedTheoryFactor(
    val source: Factor,
    override val linearForm: LinearForm,
    val attribution: EncodingAttribution,
) : Factor by source {
    override val variables = MixedVars(
        boundInts = source.intVars,
        boolVars = source.boolVars,
        reals = (source.variables.reals.toList() + attribution.auxiliaries.map { it.index }).distinct().toIntArray(),
    )
}

internal fun PreparedFactorProjections.linearTheoryModel(
    target: EncodingTarget,
    indices: List<Int> = problem.factors.indices.toList(),
    fixedIntegers: Map<SourceIntId, BigInt> = emptyMap(),
    cancellation: Cancellation = Cancellation.Never,
    fixedBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
): Problem? {
    require(target != EncodingTarget.POLYNOMIAL)
    val integerPins = fixedIntegers.toMap()
    val booleanPins = fixedBooleans.toMap()
    val factors = ArrayList<Factor>(indices.size)
    val origins = ArrayList<Int>()
    val allocator = EncodingRealAllocator(problem.numRealVars)
    for (index in indices) {
        val relations = installLinearFactor(index, target, integerPins, booleanPins, cancellation, allocator)
            ?: return null
        factors += relations
        repeat(relations.size) { origins += index }
    }
    return EncodedTheoryModel(problem, factors.toTypedArray(),
        problem.impliedFactorMask?.let { mask -> BooleanArray(factors.size) { local ->
            mask[origins[local]]
        } }, allocator.count)
}

internal fun PreparedFactorProjections.installLinearFactor(
    index: Int,
    target: EncodingTarget,
    integerPins: Map<SourceIntId, BigInt> = emptyMap(),
    booleanPins: Map<SourceBoolId, Boolean> = emptyMap(),
    cancellation: Cancellation = Cancellation.Never,
    allocator: EncodingRealAllocator = EncodingRealAllocator(problem.numRealVars),
): List<EncodedTheoryFactor>? {
    val source = problem.factors[index]
    val sourceIndex = (source as? EncodedTheoryFactor)?.attribution?.sourceFactorIndex ?: index
    val booleans = source.boolVars.toMutableSet()
    val integers = source.intVars.toMutableSet()
    val reals = source.variables.reals.toMutableSet()
    source.linearForm?.rows?.forEach { row ->
        if (row.activator != LinearRow.ALWAYS) booleans += row.activator
        for (term in 0 until row.size) {
            val ref = row.ref(term)
            when {
                Term.isInt(ref) -> integers += Term.intVar(ref)
                Term.isReal(ref) -> reals += Term.realVar(ref)
                else -> booleans += Term.lit(ref) ushr 1
            }
        }
    }
    val encoding = LinearTheoryEncoding(target, sourceIndex, integerPins, booleanPins, cancellation,
        booleans, integers, reals, allocator)
    val forms = try {
        if (encoder(index, target).install(source, encoding) != null) return null
        encoding.finish() ?: return null
    } catch (_: LinearEncodingInterrupted) {
        return null
    }
    if (cancellation()) return null
    val attribution = if (source is EncodedTheoryFactor && integerPins.isEmpty() && booleanPins.isEmpty() &&
        encoding.auxiliaryDefinitions().isEmpty() && encoding.guardDefinitions().isEmpty() &&
        encoding.realAuxiliaries.isEmpty()
    ) source.attribution else EncodingAttribution(sourceIndex,
        booleans.sorted().map(::SourceBoolId), integers.sorted().map(::SourceIntId),
        reals.sorted().map(::SourceRealId), integerConditions = integerPins,
        booleanConditions = booleanPins, auxiliaries = encoding.realAuxiliaries,
        affineAuxiliaries = encoding.auxiliaryDefinitions(),
        linearGuards = encoding.guardDefinitions())
    return forms.map { EncodedTheoryFactor(source, it, attribution) }
}

private class LinearTheoryEncoding(
    override val target: EncodingTarget,
    override val sourceFactorIndex: Int,
    private val fixedIntegers: Map<SourceIntId, BigInt>,
    private val fixedBooleans: Map<SourceBoolId, Boolean>,
    override val cancellation: Cancellation,
    private val booleans: Set<Int>,
    private val integers: Set<Int>,
    private val reals: Set<Int>,
    private val allocator: EncodingRealAllocator,
) : TheoryEncoding {
    private val owner = Any()
    private val formulas = ArrayList<LinearEncodingFormula>()
    private val auxiliaries = ArrayList<EncodingAffineExpression?>()
    private val guards = ArrayList<LinearEncodingFormula?>()
    private val lowering = LinearFormulaLowering(cancellation)
    val realAuxiliaries = ArrayList<TheoryRealId>()
    private val usedReals = HashSet<TheoryRealId>()

    override fun fixedInteger(id: SourceIntId): BigInt? = fixedIntegers[id]
    override fun fixedBoolean(id: SourceBoolId): Boolean? = fixedBooleans[id]

    override fun linear(form: LinearForm) { linearFormula(LinearEncodingFormula.Relation(form)) }

    override fun linearFormula(formula: LinearEncodingFormula) {
        lowering.checkpoint()
        formulas += formula
    }

    override fun realAuxiliary(): EncodingRealAuxiliary {
        require(target == EncodingTarget.LINEAR) { "private real coordinates require a linear consumer" }
        lowering.checkpoint()
        return EncodingRealAuxiliary(owner, allocator.allocate()).also { realAuxiliaries += it.coordinate }
    }

    override fun auxiliary(): EncodingAuxiliary {
        lowering.checkpoint()
        return EncodingAuxiliary(owner, auxiliaries.size).also { auxiliaries += null }
    }

    override fun define(auxiliary: EncodingAuxiliary, expression: EncodingAffineExpression) {
        require(auxiliary.owner === owner) { "foreign encoding auxiliary" }
        require(auxiliaries[auxiliary.index] == null) { "duplicate encoding auxiliary definition" }
        lowering.checkpoint()
        auxiliaries[auxiliary.index] = expression
    }

    override fun guard(): EncodingGuard {
        lowering.checkpoint()
        return EncodingGuard(owner, guards.size).also { guards += null }
    }

    override fun define(guard: EncodingGuard, formula: LinearEncodingFormula) {
        require(guard.owner === owner) { "foreign encoding guard" }
        require(guards[guard.index] == null) { "duplicate encoding guard definition" }
        lowering.checkpoint()
        guards[guard.index] = formula
    }

    fun auxiliaryDefinitions(): List<EncodingAffineExpression> = auxiliaries.map { checkNotNull(it) }
    fun guardDefinitions(): List<LinearEncodingFormula> = guards.map { checkNotNull(it) }

    fun finish(): List<LinearForm>? {
        if (formulas.isEmpty()) return null
        require(auxiliaries.none { it == null }) { "missing encoding auxiliary definition" }
        require(guards.none { it == null }) { "missing encoding guard definition" }
        for (index in auxiliaries.indices) expand(checkNotNull(auxiliaries[index]), setOf(index))
        for (index in guards.indices) clauses(checkNotNull(guards[index]), setOf(index))
        val forms = lowering.forms(lowering.and(formulas.map { clauses(it) }))
        require(usedReals.containsAll(realAuxiliaries)) { "unattributed private real coordinate" }
        return forms
    }

    private fun validate(reference: Int) {
        require(when {
            Term.isInt(reference) -> Term.intVar(reference) in integers
            Term.isReal(reference) -> Term.realVar(reference) in reals
            Term.isBool(reference) -> (Term.lit(reference) ushr 1) in booleans
            else -> false
        }) { "foreign source coordinate in encoding" }
    }

    private fun expand(
        expression: EncodingAffineExpression,
        active: Set<Int> = emptySet(),
    ): Pair<Map<Int, BigFraction>, BigFraction> {
        val coefficients = LinkedHashMap<Int, BigFraction>()
        var constant = expression.constant
        for (term in expression.terms) {
            lowering.checkpoint()
            val auxiliary = term.auxiliary
            val privateReal = term.realAuxiliary
            if (privateReal != null) {
                require(privateReal.owner === owner) { "foreign encoding real auxiliary" }
                usedReals += privateReal.coordinate
                val reference = Term.ofRealVar(privateReal.coordinate.index)
                coefficients[reference] = (coefficients[reference] ?: BigFraction.ZERO) + term.coefficient
            } else if (auxiliary == null) {
                val reference = checkNotNull(term.reference)
                validate(reference)
                coefficients[reference] = (coefficients[reference] ?: BigFraction.ZERO) + term.coefficient
            } else {
                require(auxiliary.owner === owner) { "foreign encoding auxiliary" }
                require(auxiliary.index !in active) { "cyclic encoding auxiliary definition" }
                val (nested, offset) = expand(checkNotNull(auxiliaries[auxiliary.index]), active + auxiliary.index)
                constant += offset * term.coefficient
                for ((reference, value) in nested) {
                    lowering.checkpoint()
                    coefficients[reference] = (coefficients[reference] ?: BigFraction.ZERO) + value * term.coefficient
                }
            }
        }
        return coefficients.filterValues { it != BigFraction.ZERO } to constant
    }

    private fun clauses(
        formula: LinearEncodingFormula,
        active: Set<Int> = emptySet(),
    ): List<List<LinearRow>> {
        lowering.checkpoint()
        return when (formula) {
            is LinearEncodingFormula.Relation -> {
                for (row in formula.form.rows) {
                    lowering.checkpoint(row.size.toLong() + 1)
                    require(row.activator == LinearRow.ALWAYS || row.activator in booleans)
                    for (term in 0 until row.size) validate(row.ref(term))
                }
                lowering.relation(formula.form)
            }
            is LinearEncodingFormula.Comparison -> {
                val (terms, constant) = expand(formula.expression)
                val row = ExactSearchRow.of(terms.keys.toIntArray(), terms.values.toList(), formula.relation,
                    formula.bound - constant, formula.strict)
                lowering.relation(row.linearForm)
            }
            is LinearEncodingFormula.Guard -> {
                require(formula.guard.owner === owner) { "foreign encoding guard" }
                val index = formula.guard.index
                require(index !in active) { "cyclic encoding guard definition" }
                clauses(checkNotNull(guards[index]), active + index)
            }
            is LinearEncodingFormula.And -> lowering.and(formula.operands.map { clauses(it, active) })
            is LinearEncodingFormula.Or -> lowering.or(formula.operands.map { clauses(it, active) })
        }
    }

    override fun polynomial(formula: PolynomialRealFormula): Unit = error("linear target cannot install a polynomial")
    override fun integer(id: SourceIntId): PolynomialExpression = error("linear target uses source integer terms")
    override fun real(id: SourceRealId): PolynomialExpression = error("linear target uses source real terms")
    override fun conditional(
        condition: PolynomialRealFormula,
        whenTrue: PolynomialExpression,
        whenFalse: PolynomialExpression,
    ): PolynomialExpression = error("linear target uses source Boolean guards")
}
