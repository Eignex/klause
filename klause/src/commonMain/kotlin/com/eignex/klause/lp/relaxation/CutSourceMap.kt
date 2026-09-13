package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class CutColumnSource(
    val source: CutSource,
    val scale: BigFraction = BigFraction.ONE,
    val offset: BigFraction = BigFraction.ZERO,
) {
    init {
        require(!scale.isZero)
    }
    fun expression(): CutExpression = CutExpression(mapOf(source to scale), offset)
}

internal class LpAuxiliarySources {
    private val identities = HashMap<CutAuxiliaryDefinition, CutSource>()
    fun matches(source: CutSource, definition: CutAuxiliaryDefinition): Boolean = identities[definition] == source
    fun source(definition: CutAuxiliaryDefinition): CutSource = identities.getOrPut(definition) {
        CutSource(CutSourceKind.AUXILIARY, identities.size)
    }
}

internal class CutSourceMap(
    val model: Any,
    val epoch: Long,
    columns: List<CutColumnSource?>,
    globalPremises: Set<CutPremise> = emptySet(),
    activePremises: Set<CutPremise> = emptySet(),
    fixed: Map<CutSource, BigFraction> = emptyMap(),
    assumptions: Set<String> = emptySet(),
    parentRows: Map<Int, CutProvenance> = emptyMap(),
    auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> = emptyMap(),
) {
    private val columnSnapshot = columns.toList()
    private val globalSnapshot = globalPremises.toSet()
    private val activeSnapshot = activePremises.toSet()
    private val fixedSnapshot = fixed.toMap()
    private val assumptionSnapshot = assumptions.toSet()
    private val parentSnapshot = parentRows.toMap()
    private val auxiliarySnapshot = auxiliaryDefinitions.toMap()
    val auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> get() = auxiliarySnapshot.toMap()
    val columns: List<CutColumnSource?> get() = columnSnapshot.toList()
    val fixed: Map<CutSource, BigFraction> get() = fixedSnapshot.toMap()
    val assumptions: Set<String> get() = assumptionSnapshot.toSet()
    fun column(index: Int): CutColumnSource? = columnSnapshot.getOrNull(index)
    fun columnForInt(variable: Int): CutColumnSource? = columnSnapshot.firstOrNull {
        it?.source == CutSource(CutSourceKind.INTEGER, variable)
    }
    fun parent(row: Int): CutProvenance? = parentSnapshot[row]
    fun isGlobal(premise: CutPremise): Boolean = implied(premise, globalSnapshot) ||
        (
            premise is CutPremise.Integral && premise.expression.constant.den == BigInteger.ONE &&
                premise.expression.terms.all { (source, coefficient) ->
                    coefficient.den == BigInteger.ONE &&
                        (source.kind == CutSourceKind.INTEGER || source.kind == CutSourceKind.BOOLEAN)
                }
            )
    fun isActive(premise: CutPremise): Boolean = isGlobal(premise) || implied(premise, activeSnapshot)

    fun presenceGuard(premise: CutPremise.Bound): CutPremise.Excluded? {
        val term = premise.expression.terms.entries.singleOrNull() ?: return null
        if (term.value != BigFraction.ONE || !premise.expression.constant.isZero || !premise.upper ||
            !premise.value.isZero || premise.strict
        ) {
            return null
        }
        val definition = auxiliarySnapshot[term.key] ?: return null
        for (index in definition.required.indices step 2) {
            val absent = CutPremise.Excluded(
                CutSource(CutSourceKind.INTEGER, definition.required[index].toInt()),
                BigFraction.ofLong(definition.required[index + 1]),
            )
            if (isActive(absent)) return absent
        }
        return null
    }

    private fun implied(premise: CutPremise, facts: Set<CutPremise>): Boolean {
        if (premise in facts) return true
        if (premise is CutPremise.Fixed) {
            val expression = CutExpression(mapOf(premise.source to BigFraction.ONE))
            return implied(CutPremise.Bound(expression, false, premise.value), facts) &&
                implied(CutPremise.Bound(expression, true, premise.value), facts)
        }
        if (premise !is CutPremise.Bound) return false
        return facts.filterIsInstance<CutPremise.Bound>().any {
            val matching = it.expression == premise.expression && it.upper == premise.upper
            val stronger = if (it.upper) it.value < premise.value else it.value > premise.value
            matching && (stronger || (it.value == premise.value && (!premise.strict || it.strict)))
        }
    }

    fun withBounds(bounds: LpModel, active: Set<CutPremise> = emptySet()): CutSourceMap = CutSourceMap(
        model,
        epoch + 1,
        columnSnapshot,
        globalSnapshot,
        columnBounds(bounds, columnSnapshot) + active,
        fixedSnapshot,
        assumptionSnapshot,
        parentSnapshot,
        auxiliarySnapshot,
    )

    fun atEpoch(next: Long): CutSourceMap = CutSourceMap(
        model,
        next,
        columnSnapshot,
        globalSnapshot,
        activeSnapshot,
        fixedSnapshot,
        assumptionSnapshot,
        parentSnapshot,
        auxiliarySnapshot,
    )

    /** Compact row-indexed cut provenance through a proof-mapped relaxation transform. */
    fun remapRows(sourceRows: IntArray): CutSourceMap {
        val remapped = HashMap<Int, CutProvenance>()
        for (outputRow in sourceRows.indices) {
            parentSnapshot[sourceRows[outputRow]]?.let { remapped[outputRow] = it }
        }
        return CutSourceMap(
            model,
            epoch,
            columnSnapshot,
            globalSnapshot,
            activeSnapshot,
            fixedSnapshot,
            assumptionSnapshot,
            remapped,
            auxiliarySnapshot,
        )
    }
}

internal fun CutSourceMap.withCpBounds(
    model: LpModel,
    session: com.eignex.klause.propagation.PropagationSession,
): CutSourceMap {
    val facts = HashSet<CutPremise>()
    for (variable in 0 until session.problem.numBoolVars) {
        val value = session.boolValue(variable) ?: continue
        facts.add(CutPremise.Literal((variable shl 1) or if (value) 0 else 1))
    }
    for (variable in 0 until session.problem.numIntVars) {
        val domain = session.intDomain(variable)
        val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, variable) to BigFraction.ONE))
        facts.add(CutPremise.Bound(expression, false, BigFraction.ofLong(domain.min)))
        facts.add(CutPremise.Bound(expression, true, BigFraction.ofLong(domain.max)))
    }
    for (definition in auxiliaryDefinitions.values) {
        for (index in definition.required.indices step 2) {
            val variable = definition.required[index].toInt()
            val value = definition.required[index + 1]
            if (!session.intDomain(variable).contains(value)) {
                facts.add(
                    CutPremise.Excluded(CutSource(CutSourceKind.INTEGER, variable), BigFraction.ofLong(value)),
                )
            }
        }
    }
    return withBounds(model, facts)
}

internal fun cpCutSources(
    model: LpModel,
    problem: com.eignex.klause.ir.Problem,
    vars: IntArray,
    booleans: BooleanArray,
    realIds: IntArray,
    realSigns: IntArray,
    parents: Map<Int, CutProvenance>,
    presence: List<CutAuxiliaryDefinition?> = List(model.n) { null },
    auxiliarySources: LpAuxiliarySources = LpAuxiliarySources(),
    domains: RelaxationDomains? = null,
): CutSourceMap {
    val globals = HashSet<CutPremise>()
    val auxiliary = HashMap<CutSource, CutAuxiliaryDefinition>()
    val realCounts = realIds.filter { it >= 0 }.groupingBy { it }.eachCount()
    val columns = List(model.n) { col ->
        val real = realIds[col]
        val source = when {
            vars[col] >= 0 -> CutSource(if (booleans[col]) CutSourceKind.BOOLEAN else CutSourceKind.INTEGER, vars[col])

            real >= 0 && realSigns[col] == 1 && realCounts[real] == 1 -> CutSource(CutSourceKind.REAL, real)

            presence[col] != null -> auxiliarySources.source(requireNotNull(presence[col])).also {
                auxiliary[it] = requireNotNull(presence[col])
            }

            else -> null
        }
        source?.let { CutColumnSource(it) }
    }
    for (column in columns.filterNotNull()) {
        val source = column.source
        val expression = column.expression()
        when (source.kind) {
            CutSourceKind.INTEGER -> {
                globals.add(CutPremise.Integral(expression))
                if (problem.intBounds.hasLower(source.id)) {
                    globals.add(
                        CutPremise.Bound(expression, false, BigFraction.ofLong(problem.intBounds.lower(source.id))),
                    )
                }
                if (problem.intBounds.hasUpper(source.id)) {
                    globals.add(
                        CutPremise.Bound(expression, true, BigFraction.ofLong(problem.intBounds.upper(source.id))),
                    )
                }
            }

            CutSourceKind.BOOLEAN -> {
                globals.add(CutPremise.Integral(expression))
                globals.add(CutPremise.Bound(expression, false, BigFraction.ZERO))
                globals.add(CutPremise.Bound(expression, true, BigFraction.ONE))
            }

            CutSourceKind.REAL -> {
                BigFraction.ofDouble(
                    problem.realLower[source.id],
                )?.let { globals.add(CutPremise.Bound(expression, false, it)) }
                BigFraction.ofDouble(
                    problem.realUpper[source.id],
                )?.let { globals.add(CutPremise.Bound(expression, true, it)) }
            }

            CutSourceKind.TERM -> Unit

            CutSourceKind.AUXILIARY -> {
                val definition = requireNotNull(auxiliary[source])
                globals.add(CutPremise.Bound(expression, false, BigFraction.ZERO))
                globals.add(CutPremise.Bound(expression, true, BigFraction.ofLong(definition.presentUpper)))
                if (definition.integralExtension) globals.add(CutPremise.Integral(expression))
            }
        }
    }
    val active = columnBounds(model, columns).toMutableSet()
    if (domains != null) {
        for (definition in auxiliary.values) {
            for (index in definition.required.indices step 2) {
                val variable = definition.required[index].toInt()
                val value = definition.required[index + 1]
                if (!domains.intDomain(variable).contains(value)) {
                    active.add(
                        CutPremise.Excluded(CutSource(CutSourceKind.INTEGER, variable), BigFraction.ofLong(value)),
                    )
                }
            }
        }
    }
    return CutSourceMap(problem, 0, columns, globals, active, parentRows = parents, auxiliaryDefinitions = auxiliary)
}

private fun columnBounds(model: LpModel, columns: List<CutColumnSource?>): Set<CutPremise> {
    val active = HashSet<CutPremise>()
    for ((index, column) in columns.withIndex()) {
        if (column == null) continue
        val bounds = model.exactBounds(index)
        val origin = model.exactShift(index)
        bounds.lower?.let {
            active.add(
                CutPremise.Bound(column.expression(), false, it.number.value + origin, it.strict),
            )
        }
        bounds.upper?.let {
            active.add(
                CutPremise.Bound(column.expression(), true, it.number.value + origin, it.strict),
            )
        }
        if (column.source.kind == CutSourceKind.BOOLEAN) {
            if (bounds.lower?.number?.value?.plus(origin) == BigFraction.ONE) {
                active.add(CutPremise.Literal(column.source.id shl 1))
            }
            if (bounds.upper?.number?.value?.plus(origin) == BigFraction.ZERO) {
                active.add(CutPremise.Literal((column.source.id shl 1) or 1))
            }
        }
    }
    return active
}

internal fun cutProofApplies(
    proof: CutProvenance,
    problem: com.eignex.klause.ir.Problem,
    domains: RelaxationDomains,
): Boolean {
    if (proof.model !== problem || proof.assumptions.isNotEmpty()) return false
    return proof.facts.all { fact ->
        fact.global || when (val premise = fact.premise) {
            is CutPremise.Bound -> {
                val endpoint = sourceEndpoint(premise.expression, premise.upper, problem, domains)
                endpoint != null && if (premise.upper) {
                    endpoint < premise.value || (!premise.strict && endpoint == premise.value)
                } else {
                    endpoint > premise.value || (!premise.strict && endpoint == premise.value)
                }
            }

            is CutPremise.Fixed -> {
                val expression = CutExpression(mapOf(premise.source to BigFraction.ONE))
                sourceEndpoint(expression, false, problem, domains) == premise.value &&
                    sourceEndpoint(expression, true, problem, domains) == premise.value
            }

            is CutPremise.Excluded -> {
                val value = ExactLpNumber.of(premise.value).legacyLong()
                premise.source.kind == CutSourceKind.INTEGER && premise.source.id in 0 until problem.numIntVars &&
                    value != null && !domains.intDomain(premise.source.id).contains(value)
            }

            is CutPremise.Literal -> (premise.literal ushr 1) in 0 until problem.numBoolVars &&
                domains.boolValue(premise.literal ushr 1) == (premise.literal and 1 == 0)

            else -> false
        }
    }
}

private fun sourceEndpoint(
    expression: CutExpression,
    upper: Boolean,
    problem: com.eignex.klause.ir.Problem,
    domains: RelaxationDomains,
): BigFraction? {
    var value = expression.constant
    for ((source, coefficient) in expression.terms) {
        val high = upper == (coefficient.signum() > 0)
        val endpoint = when (source.kind) {
            CutSourceKind.INTEGER -> {
                if (source.id !in 0 until problem.numIntVars) return null
                val domain = domains.intDomain(source.id)
                BigFraction.ofLong(if (high) domain.max else domain.min)
            }

            CutSourceKind.BOOLEAN -> {
                if (source.id !in 0 until problem.numBoolVars) return null
                val pinned = domains.boolValue(source.id)
                if (pinned == true || (pinned == null && high)) BigFraction.ONE else BigFraction.ZERO
            }

            CutSourceKind.REAL -> {
                if (source.id !in problem.realLower.indices) return null
                BigFraction.ofDouble(
                    if (high) problem.realUpper[source.id] else problem.realLower[source.id],
                ) ?: return null
            }

            CutSourceKind.TERM, CutSourceKind.AUXILIARY -> return null
        }
        value += coefficient * endpoint
    }
    return value
}
