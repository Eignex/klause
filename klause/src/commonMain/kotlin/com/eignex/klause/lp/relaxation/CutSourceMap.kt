package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.LpLayoutWeight
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction

internal data class CutColumnSource(
    val source: CutSource,
    val scale: BigFraction = BigFraction.ONE,
    val offset: BigFraction = BigFraction.ZERO,
) {
    init {
        require(!scale.isZero)
    }
    private val expression = CutExpression(mapOf(source to scale), offset)
    fun expression(): CutExpression = expression
}

internal class LpAuxiliarySources {
    private var identities = HashMap<CutAuxiliaryDefinition, CutSource>()
    private var nextIdentity = 0L
    var generation: Long = 0L
        private set
    var storageUnits: Long = 0L
        private set
    val size: Int get() = identities.size
    fun matches(source: CutSource, definition: CutAuxiliaryDefinition): Boolean = identities[definition] == source
    fun source(definition: CutAuxiliaryDefinition): CutSource = identities.getOrPut(definition) {
        check(nextIdentity <= Int.MAX_VALUE.toLong()) { "auxiliary source identity exhausted" }
        check(generation < Long.MAX_VALUE)
        generation++
        storageUnits += definition.storageUnits + 2L
        CutSource(CutSourceKind.AUXILIARY, (nextIdentity++).toInt())
    }

    fun retain(definitions: Set<CutAuxiliaryDefinition>) {
        if (identities.keys.all { it in definitions }) return
        check(generation < Long.MAX_VALUE)
        val retained = HashMap<CutAuxiliaryDefinition, CutSource>(minOf(identities.size, definitions.size))
        for ((definition, source) in identities) if (definition in definitions) retained[definition] = source
        identities = retained
        storageUnits = identities.keys.sumOf { it.storageUnits + 2L }
        generation++
    }

    fun storageWeight(definitions: Set<CutAuxiliaryDefinition>): LpLayoutWeight {
        val kept = identities.keys.sumOf { if (it in definitions) it.storageUnits + 2L else 0L }
        return LpLayoutWeight(kept, storageUnits - kept)
    }
}

private class CutSourceDefinition(
    val columns: List<CutColumnSource?>,
    val globals: Set<CutPremise>,
    val fixed: Map<CutSource, BigFraction>,
    val assumptions: Set<String>,
    val auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition>,
)

private class CutLpBounds(val identity: Any?, val facts: Set<CutPremise>)

internal class CutSourceMap private constructor(
    val model: Any,
    val epoch: Long,
    private val sourceDefinition: CutSourceDefinition,
    private val activeSnapshot: Set<CutPremise>,
    private val parentSnapshot: Map<Int, CutProvenance>,
    private val cpBounds: CpCutBounds?,
    private val lpBounds: CutLpBounds?,
) {
    constructor(
        model: Any,
        epoch: Long,
        columns: List<CutColumnSource?>,
        globalPremises: Set<CutPremise> = emptySet(),
        activePremises: Set<CutPremise> = emptySet(),
        fixed: Map<CutSource, BigFraction> = emptyMap(),
        assumptions: Set<String> = emptySet(),
        parentRows: Map<Int, CutProvenance> = emptyMap(),
        auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> = emptyMap(),
    ) : this(
        model, epoch,
        CutSourceDefinition(columns.toList(), globalPremises.toSet(), fixed.toMap(),
            assumptions.toSet(), auxiliaryDefinitions.toMap()),
        activePremises.toSet(), parentRows.toMap(), null, null,
    )

    private val auxiliaryByDefinition: Map<CutAuxiliaryDefinition, CutSource?> by lazy(
        LazyThreadSafetyMode.PUBLICATION,
    ) {
        val unique = HashMap<CutAuxiliaryDefinition, CutSource?>()
        for ((source, definition) in sourceDefinition.auxiliaryDefinitions) {
            if (source.kind != CutSourceKind.AUXILIARY) continue
            unique[definition] = if (definition in unique) null else source
        }
        unique
    }
    val auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition>
        get() = sourceDefinition.auxiliaryDefinitions.toMap()
    val columns: List<CutColumnSource?> get() = sourceDefinition.columns.toList()
    val columnCount: Int get() = sourceDefinition.columns.size
    val fixed: Map<CutSource, BigFraction> get() = sourceDefinition.fixed.toMap()
    val assumptions: Set<String> get() = sourceDefinition.assumptions.toSet()
    fun column(index: Int): CutColumnSource? = sourceDefinition.columns.getOrNull(index)
    fun columnForInt(variable: Int): CutColumnSource? = sourceDefinition.columns.firstOrNull {
        it?.source == CutSource(CutSourceKind.INTEGER, variable)
    }
    fun parent(row: Int): CutProvenance? = parentSnapshot[row]
    fun parentFamilies(): Map<Int, CutFamily> = parentSnapshot.mapValues { it.value.family }
    fun auxiliarySource(source: CutSource, definition: CutAuxiliaryDefinition): CutSource? {
        if (source.kind != CutSourceKind.AUXILIARY) return null
        if (sourceDefinition.auxiliaryDefinitions[source] == definition) return source
        return auxiliaryByDefinition[definition]
    }
    fun isGlobal(premise: CutPremise): Boolean = implied(premise, sourceDefinition.globals) ||
        (
            premise is CutPremise.Integral && premise.expression.constant.den == BIG_ONE &&
                premise.expression.terms.all { (source, coefficient) ->
                    coefficient.den == BIG_ONE &&
                        (source.kind == CutSourceKind.INTEGER || source.kind == CutSourceKind.BOOLEAN)
                }
            )
    fun isActive(premise: CutPremise): Boolean = isGlobal(premise) || impliedActive(premise)

    private fun impliedActive(premise: CutPremise): Boolean {
        if (premise in activeSnapshot || lpBounds?.facts?.contains(premise) == true) return true
        if (premise is CutPremise.Fixed) {
            val expression = CutExpression(mapOf(premise.source to BigFraction.ONE))
            return impliedActive(CutPremise.Bound(expression, false, premise.value)) &&
                impliedActive(CutPremise.Bound(expression, true, premise.value))
        }
        return cpBounds?.implies(premise) == true || implied(premise, activeSnapshot) ||
            lpBounds?.facts?.let { implied(premise, it) } == true
    }

    fun presenceGuard(premise: CutPremise.Bound): CutPremise.Excluded? {
        val term = premise.expression.terms.entries.singleOrNull() ?: return null
        if (term.value != BigFraction.ONE || !premise.expression.constant.isZero || !premise.upper ||
            !premise.value.isZero || premise.strict
        ) {
            return null
        }
        val definition = sourceDefinition.auxiliaryDefinitions[term.key] ?: return null
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

    fun withBounds(
        bounds: LpModel,
        active: Set<CutPremise> = emptySet(),
        onWork: ((CutSourceWork) -> Unit)? = null,
    ): CutSourceMap = measureCutSourceWork(onWork) { work ->
        val lp = captureBounds(bounds.exactState?.boundSnapshotIdentity, work) { column ->
            bounds.exactBounds(column) to bounds.exactShift(column)
        }
        work.structural(active.size.toLong())
        withBoundSnapshot(active.toSet(), lp = lp)
    }

    fun withDomainBounds(
        model: LpModel,
        domains: RelaxationDomains,
        onWork: ((CutSourceWork) -> Unit)? = null,
    ): CutSourceMap = measureCutSourceWork(onWork) { work ->
        val lp = captureBounds(model.exactState?.boundSnapshotIdentity, work) { column ->
            model.exactBounds(column) to model.exactShift(column)
        }
        withBoundSnapshot(domainExclusions(domains, work), lp = lp)
    }

    fun bindCpLayout(
        domains: RelaxationDomains?,
        parents: Map<Int, CutProvenance>,
        onWork: ((CutSourceWork) -> Unit)? = null,
        boundIdentity: Any? = null,
        bound: (Int) -> Pair<ExactLpBounds, BigFraction>,
    ): CutSourceMap = measureCutSourceWork(onWork) { work ->
        val lp = captureBounds(boundIdentity, work, bound)
        val active = domainExclusions(domains, work)
        work.structural(parents.size.toLong() + 1L)
        CutSourceMap(model, epoch, sourceDefinition, active, parents.toMap(), null, lp)
    }

    private fun captureBounds(
        identity: Any?,
        work: CutSourceWorkMeter,
        bound: (Int) -> Pair<ExactLpBounds, BigFraction>,
    ): CutLpBounds {
        work.structural()
        return lpBounds?.takeIf { identity != null && it.identity === identity }
            ?: CutLpBounds(identity, columnBounds(sourceDefinition.columns, work, bound))
    }

    private fun domainExclusions(
        domains: RelaxationDomains?,
        work: CutSourceWorkMeter,
    ): Set<CutPremise> {
        val active = HashSet<CutPremise>()
        if (domains != null) {
            for (definition in sourceDefinition.auxiliaryDefinitions.values) {
                val required = definition.required
                work.structural(required.size.toLong() + 1L)
                for (index in required.indices step 2) {
                    work.structural()
                    val variable = required[index].toInt()
                    val value = required[index + 1]
                    if (!domains.intDomain(variable).contains(value)) {
                        work.exact()
                        active.add(
                            CutPremise.Excluded(CutSource(CutSourceKind.INTEGER, variable), BigFraction.ofLong(value)),
                        )
                    }
                }
            }
        }
        return active
    }

    private fun withBoundSnapshot(
        active: Set<CutPremise>,
        cp: CpCutBounds? = null,
        lp: CutLpBounds? = null,
    ): CutSourceMap = CutSourceMap(
        model,
        epoch + 1,
        sourceDefinition, active, parentSnapshot, cp, lp,
    )

    fun withCpBounds(
        model: LpModel,
        session: com.eignex.klause.propagation.PropagationSession,
        onWork: ((CutSourceWork) -> Unit)? = null,
    ): CutSourceMap = measureCutSourceWork(onWork) { work ->
        val lp = captureBounds(model.exactState?.boundSnapshotIdentity, work) { column ->
            model.exactBounds(column) to model.exactShift(column)
        }
        val cp = CpCutBounds.capture(session, work)
        withBoundSnapshot(domainExclusions(SessionDomains(session), work), cp, lp)
    }

    fun withParentRows(
        parents: Map<Int, CutProvenance>,
        onWork: ((CutSourceWork) -> Unit)? = null,
    ): CutSourceMap = measureCutSourceWork(onWork) { work ->
        work.structural(parents.size.toLong())
        CutSourceMap(model, epoch, sourceDefinition, activeSnapshot, parents.toMap(), cpBounds, lpBounds)
    }

    fun withActivePremises(premises: Set<CutPremise>): CutSourceMap = CutSourceMap(
        model, epoch, sourceDefinition, activeSnapshot + premises, parentSnapshot, cpBounds, lpBounds,
    )

    companion object {
        // The CP layout builder transfers fresh collections; bound publications share their immutable contents.
        fun ownedCpLayout(
            model: Any,
            columns: List<CutColumnSource?>,
            globals: Set<CutPremise>,
            auxiliary: Map<CutSource, CutAuxiliaryDefinition>,
        ): CutSourceMap = CutSourceMap(
            model, 0L, CutSourceDefinition(columns, globals, emptyMap(), emptySet(), auxiliary),
            emptySet(), emptyMap(), null, null,
        )
    }
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
    onWork: ((CutSourceWork) -> Unit)? = null,
): CutSourceMap = cpCutSources(
    problem, vars, booleans, realIds, realSigns, parents, presence, auxiliarySources, domains, onWork,
) { column -> model.exactBounds(column) to model.exactShift(column) }

internal fun cpCutSources(
    problem: com.eignex.klause.ir.Problem,
    vars: IntArray,
    booleans: BooleanArray,
    realIds: IntArray,
    realSigns: IntArray,
    parents: Map<Int, CutProvenance>,
    presence: List<CutAuxiliaryDefinition?>,
    auxiliarySources: LpAuxiliarySources,
    domains: RelaxationDomains?,
    onWork: ((CutSourceWork) -> Unit)? = null,
    bound: (Int) -> Pair<ExactLpBounds, BigFraction>,
): CutSourceMap = measureCutSourceWork(onWork) { work ->
    cpCutSourceLayout(problem, vars, booleans, realIds, realSigns, presence, auxiliarySources, work::add)
        .bindCpLayout(domains, parents, work::add, bound = bound)
}

internal fun cpCutSourceLayout(
    problem: com.eignex.klause.ir.Problem,
    vars: IntArray,
    booleans: BooleanArray,
    realIds: IntArray,
    realSigns: IntArray,
    presence: List<CutAuxiliaryDefinition?>,
    auxiliarySources: LpAuxiliarySources,
    onWork: ((CutSourceWork) -> Unit)? = null,
): CutSourceMap = measureCutSourceWork(onWork) { work ->
    val globals = HashSet<CutPremise>()
    val auxiliary = HashMap<CutSource, CutAuxiliaryDefinition>()
    val realCounts = HashMap<Int, Int>()
    for (real in realIds) {
        work.structural()
        if (real >= 0) realCounts[real] = (realCounts[real] ?: 0) + 1
    }
    val columns = List(vars.size) { col ->
        work.structural()
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
    work.structural(columns.size.toLong())
    for (column in columns.filterNotNull()) {
        work.structural()
        val source = column.source
        val expression = column.expression()
        when (source.kind) {
            CutSourceKind.INTEGER -> {
                work.structural()
                globals.add(CutPremise.Integral(expression))
                if (problem.intBounds.hasLower(source.id)) {
                    work.exact()
                    globals.add(
                        CutPremise.Bound(expression, false, BigFraction.ofLong(problem.intBounds.lower(source.id))),
                    )
                }
                if (problem.intBounds.hasUpper(source.id)) {
                    work.exact()
                    globals.add(
                        CutPremise.Bound(expression, true, BigFraction.ofLong(problem.intBounds.upper(source.id))),
                    )
                }
            }

            CutSourceKind.BOOLEAN -> {
                work.structural()
                globals.add(CutPremise.Integral(expression))
                work.exact(2L)
                globals.add(CutPremise.Bound(expression, false, BigFraction.ZERO))
                globals.add(CutPremise.Bound(expression, true, BigFraction.ONE))
            }

            CutSourceKind.REAL -> {
                BigFraction.ofDouble(
                    problem.realLower[source.id],
                )?.let {
                    work.exact()
                    globals.add(CutPremise.Bound(expression, false, it))
                }
                BigFraction.ofDouble(
                    problem.realUpper[source.id],
                )?.let {
                    work.exact()
                    globals.add(CutPremise.Bound(expression, true, it))
                }
            }

            CutSourceKind.TERM -> Unit

            CutSourceKind.AUXILIARY -> {
                val definition = requireNotNull(auxiliary[source])
                work.exact(2L)
                globals.add(CutPremise.Bound(expression, false, BigFraction.ZERO))
                globals.add(CutPremise.Bound(expression, true, BigFraction.ofLong(definition.presentUpper)))
                if (definition.integralExtension) {
                    work.structural()
                    globals.add(CutPremise.Integral(expression))
                }
            }
        }
    }
    work.structural()
    CutSourceMap.ownedCpLayout(problem, columns, globals, auxiliary)
}

private fun columnBounds(
    columns: List<CutColumnSource?>,
    work: CutSourceWorkMeter,
    bound: (Int) -> Pair<ExactLpBounds, BigFraction>,
): MutableSet<CutPremise> {
    val active = HashSet<CutPremise>()
    for ((index, column) in columns.withIndex()) {
        work.structural()
        if (column == null) continue
        val (bounds, origin) = bound(index)
        if (bounds.lower == null && bounds.upper == null) continue
        val expression = column.expression()
        bounds.lower?.let {
            work.exact()
            active.add(
                CutPremise.Bound(expression, false, it.number.value + origin, it.strict),
            )
        }
        bounds.upper?.let {
            work.exact()
            active.add(
                CutPremise.Bound(expression, true, it.number.value + origin, it.strict),
            )
        }
        if (column.source.kind == CutSourceKind.BOOLEAN) {
            work.exact(2L)
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
                val value = ExactLpNumber.of(premise.value).exactLong()
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
