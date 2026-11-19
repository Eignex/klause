package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.requireVariableCounts
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.util.ArithmeticBudget
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal data class QfNraParams(
    val cancellation: ExecutionControl = ExecutionControl.Never,
    val maximumWork: Long? = null,
    val polynomialLimits: PolynomialLimits = PolynomialLimits(),
    val cellRefinement: Boolean = false,
    val cellFeedback: Boolean = false,
    val coupledSections: Boolean = false,
    val coupledPreparation: Boolean = true,
    val coupledJoint: Boolean = true,
) {
    init {
        require(maximumWork == null || maximumWork >= 0)
    }
}

internal data class QfNraStats(
    val projectedPolynomials: Long,
    val liftedCells: Long,
    val workSpent: Long,
    val dyadicAttempts: Long? = null,
    val dyadicBases: Long? = null,
    val coupledWorkDeclines: Long? = null,
    val dyadicPreExtractionDeclines: Long? = null,
    val booleanTheoryRegions: Long? = null,
    val booleanTheoryAtoms: Long? = null,
    val booleanTheoryConstantRefutations: Long? = null,
    val booleanTheoryResourceRefusals: Long? = null,
    val lastBooleanTheoryResource: PolynomialResource? = null,
    val equationalSections: Long? = null,
    val equationalRootLevels: Long? = null,
    val envelopeQueries: Long = 0,
    val envelopeRefutations: Long = 0,
    val envelopeRefinements: Long = 0,
)

internal enum class QfNraReason { Interrupted, WorkLimit, ResourceLimit }

internal class QfNraAssignment(bools: BooleanArray, reals: List<AlgebraicReal>) {
    private val booleanValues = bools.copyOf()
    private val realValues = reals.toTypedArray()
    val boolCount: Int get() = booleanValues.size
    val realCount: Int get() = realValues.size
    val bools: BooleanArray get() = booleanValues.copyOf()
    val reals: List<AlgebraicReal> get() = realValues.toList()
    fun boolValue(variable: Int): Boolean = booleanValues[variable]
    fun realValue(variable: Int): AlgebraicReal = realValues[variable]
}

internal sealed interface QfNraResult {
    val stats: QfNraStats
    data class Satisfiable(val assignment: QfNraAssignment, override val stats: QfNraStats) : QfNraResult
    data class Unsatisfiable(override val stats: QfNraStats) : QfNraResult
    data class Unknown(
        val reason: QfNraReason,
        override val stats: QfNraStats,
        val resource: PolynomialResource? = null,
    ) : QfNraResult
}

internal fun solveQfNra(
    formula: PolynomialRealFormula,
    boolCount: Int,
    realCount: Int,
    params: QfNraParams = QfNraParams(),
    condition: PolynomialCondition = PolynomialCondition(),
): QfNraResult {
    val query = QfNraQuery(formula, boolCount, realCount, params, condition)
    try {
        while (true) query.next()?.let { return it }
    } finally {
        query.close()
    }
}

internal class QfNraQuery(
    formula: PolynomialRealFormula,
    private val boolCount: Int,
    private val realCount: Int,
    params: QfNraParams,
    condition: PolynomialCondition,
) : AutoCloseable {
    private val work = QfNraWork(params)
    private val arithmetic = PolynomialArithmetic(work, params.polynomialLimits).also {
        work.execution = it.execution
    }
    var failureCapture: PolynomialFailureCapture? = null
        set(value) {
            check(work.spentWork == 0L && result == null) { "attach failure capture before query entry" }
            if (value !== field) value?.attach()
            field = value
        }
    var checkedCellObserver: ((CheckedPolynomialCell) -> Unit)? = null
    private val cellFeedback = if (params.cellRefinement && params.cellFeedback) {
        PolynomialCellFeedback(formula, condition, parent = work, limits = params.polynomialLimits)
    } else null
    private val cellRefinement = if (params.cellRefinement) {
        PolynomialCellRefinement(formula, condition, work, params.polynomialLimits, cellFeedback) {
            checkedCellObserver?.let { observer -> arithmetic.execution.handoff { observer(it) } }
        }
    } else {
        null
    }
    private val coupledSections = if (params.coupledSections) {
        PolynomialCoupledSections(work, params.polynomialLimits,
            prepareSupports = params.coupledPreparation, joint = params.coupledJoint)
    } else null
    val coupledStats: PolynomialCoupledStats? get() = coupledSections?.stats
    var coupledConflictObserver: ((List<PolynomialAtom>) -> Unit)? = null
    val cellStats: PolynomialCellStats? get() = cellRefinement?.stats
    val cellFeedbackStats: PolynomialCellFeedbackStats? get() = cellFeedback?.stats
    val firstCellFeedbackUse: PolynomialCellFeedbackUse? get() = cellFeedback?.firstRegionUse
    val firstCellFeedbackReuse: PolynomialCellFeedbackUse? get() = cellFeedback?.firstCrossRegionUse
    val checkedCells: List<CheckedPolynomialCell> get() = cellRefinement?.cells.orEmpty()
    var projectionObserver: ((Int, List<Polynomial>, Polynomial?) -> Unit)? = null
    var reducedRegionObserver: ((List<PolynomialAtom>) -> Unit)? = null
    var partialConflictObserver: ((List<PolynomialAtom>) -> Unit)? = null
    val stats: QfNraStats get() = work.snapshot()
    val progress = PolynomialQueryProgress()
    var probeCompleted: Boolean = false
        private set
    private var result: QfNraResult? = null
    private var envelopePruning: PolynomialLpPruning? = null
    private var envelopeSpent = 0L
    private var firstAdvance = true
    private val preparation = sequence<QfNraResult?> {
        val algebraic = AlgebraicArithmetic(arithmetic)
        try {
            failureCapture?.operation("normalization", 0, 0, work.spentWork, arithmetic)
            arithmetic.step()
            val conditioned = condition.apply(formula, boolCount, realCount, arithmetic::step)
            conditioned.requireVariableCounts(boolCount, realCount, arithmetic::step)
            val fixed = condition.lower.filter { (id, value) ->
                arithmetic.step()
                condition.upper[id] == value
            }
            val normalized = PolynomialFormula.normalize(conditioned, arithmetic, fixed)
            failureCapture?.scope(normalized, condition, arithmetic)
            val mandatory = normalized.forcedAtoms(arithmetic)
            envelopePruning = mandatory?.let { PolynomialLpPruning(it, realCount, arithmetic, work) }
            cellRefinement?.let { refinement ->
                failureCapture?.operation("cell-refinement", 0, 0, work.spentWork, arithmetic)
                refinement.prepare(normalized)
                val witness = refinement.witness(realCount, fixed) { point, checking, scalars ->
                    checkSample(normalized, point, boolCount, checking, scalars)
                }
                if (witness != null) {
                    arithmetic.step()
                    yield(QfNraResult.Satisfiable(witness, work.snapshot()))
                    return@sequence
                }
            }
            yield(null)
            work.equationalSections = 0
            val preparingFormula = PolynomialFormulaReduction.stages(normalized, realCount, arithmetic) {
                val sections = checkNotNull(work.equationalSections)
                work.equationalSections = sections + minOf(1L, Long.MAX_VALUE - sections)
            }
            var reduction = checkNotNull(preparingFormula.next())
            progress.committed(reduction, work.spentWork)
            failureCapture?.context(reduction, arithmetic)
            var rootCapture = failureCapture?.cursorState()
            var searchCapture: PolynomialFailureCursorState? = null
            var searched = reduction
            var searching: Iterator<QfNraResult?>? = null
            var startedSearch = false
            var declined: QfNraResult.Unknown? = null
            while (true) {
                if (probeCompleted && envelopePruning != null &&
                    envelopeSpent <= progress.rootWork + progress.searchWork) {
                    val envelope = advanceEnvelopes(normalized, fixed, algebraic)
                    if (envelope != null) {
                        yield(envelope)
                        return@sequence
                    }
                    yield(null)
                    continue
                }
                if (!reduction.finished && (searching == null || progress.rootWork < progress.searchWork)) {
                    rootCapture?.let { failureCapture?.restoreCursor(it) }
                    failureCapture?.operation("root-reduction", progress.snapshots, progress.regions,
                        work.spentWork, arithmetic)
                    val before = work.spentWork
                    val stage = try {
                        preparingFormula.next()
                    } finally {
                        progress.rootWork += work.spentWork - before
                    }
                    if (stage != null) {
                        progress.committed(stage, work.spentWork)
                        failureCapture?.context(stage, arithmetic)
                        reduction = stage
                        if (stage.inconsistent) {
                            arithmetic.step()
                            yield(QfNraResult.Unsatisfiable(work.snapshot()))
                            return@sequence
                        }
                        if ((searching == null && (!startedSearch || stage.formula !== searched.formula)) ||
                            (stage.finished && stage.formula !== searched.formula)
                        ) {
                            searching = search(normalized, stage, fixed, algebraic)
                            searchCapture = PolynomialFailureCursorState(emptyMap(), emptyMap())
                            searched = stage
                            declined = null
                            progress.started(stage)
                            if (startedSearch) progress.searchRestarts++
                            startedSearch = true
                        }
                    }
                    rootCapture = failureCapture?.cursorState()
                } else if (searching != null) {
                    searchCapture?.let { failureCapture?.restoreCursor(it) }
                    val before = work.spentWork
                    val completed = try {
                        searching.next()
                    } catch (stop: PolynomialResourceLimit) {
                        QfNraResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), stop.resource)
                    } finally {
                        searchCapture = failureCapture?.cursorState()
                        progress.searchWork += work.spentWork - before
                    }
                    if (completed is QfNraResult.Unknown) {
                        declined = completed
                        searching = null
                        if (reduction.formula !== searched.formula) {
                            searching = search(normalized, reduction, fixed, algebraic)
                            searchCapture = PolynomialFailureCursorState(emptyMap(), emptyMap())
                            searched = reduction
                            declined = null
                            progress.started(reduction)
                            progress.searchRestarts++
                        }
                    } else if (completed != null) {
                        yield(completed)
                        return@sequence
                    }
                } else {
                    arithmetic.step()
                    yield(checkNotNull(declined).copy(stats = work.snapshot()))
                    return@sequence
                }
                yield(null)
            }
        } catch (_: PolynomialInterrupted) {
            val reason = if (work.limitReached) QfNraReason.WorkLimit else QfNraReason.Interrupted
            yield(QfNraResult.Unknown(reason, work.snapshot()))
        } catch (stop: PolynomialResourceLimit) {
            yield(QfNraResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), stop.resource))
        }
    }.iterator()

    private fun advanceEnvelopes(
        normalized: PolynomialFormula,
        fixed: Map<Int, BigFraction>,
        algebraic: AlgebraicArithmetic,
    ): QfNraResult? {
        val pruning = envelopePruning ?: return null
        val start = work.spentWork
        val step = try {
            pruning.advance { point ->
                val coordinates = (0 until realCount).associateWith { algebraic.rational(fixed[it] ?: point[it]) }
                checkSample(normalized, coordinates, boolCount, arithmetic, algebraic)
            }
        } finally {
            envelopeSpent += work.spentWork - start
        }
        return when (step) {
            PolynomialLpStep.Pending -> null
            PolynomialLpStep.Unavailable -> {
                envelopePruning = null
                null
            }
            PolynomialLpStep.Refuted -> QfNraResult.Unsatisfiable(work.snapshot())
            is PolynomialLpStep.Witness -> QfNraResult.Satisfiable(step.assignment, work.snapshot())
        }
    }

    override fun close() {
        envelopePruning?.close()
        envelopePruning = null
    }

    private fun search(
        normalized: PolynomialFormula,
        formulaReduction: PolynomialFormulaReduction,
        fixed: Map<Int, BigFraction>,
        algebraic: AlgebraicArithmetic,
    ): Iterator<QfNraResult?> = sequence<QfNraResult?> {
        if (formulaReduction.inconsistent) {
            arithmetic.step()
            yield(QfNraResult.Unsatisfiable(work.snapshot()))
            return@sequence
        }
        val reducedFormula = formulaReduction.formula
        failureCapture?.context(formulaReduction, arithmetic)
        failureCapture?.operation("probe", progress.searchSnapshot, progress.regions, work.spentWork, arithmetic)
        var refuted = false
        val witness = work.probe {
            refuted = realCount <= MAX_WITNESS_REALS && (
                refutePolynomialSigns(reducedFormula, arithmetic, MAX_WITNESS_ATOMS, MAX_WITNESS_TERMS) ||
                    refutePolynomialConsequences(reducedFormula, arithmetic, MAX_WITNESS_ATOMS, MAX_WITNESS_TERMS,
                        MAX_WITNESS_BITS) ||
                    (boolCount <= MAX_WITNESS_BOOLS && reducedFormula.refuteBooleans(arithmetic, MAX_WITNESS_ATOMS,
                        MAX_WITNESS_TERMS, MAX_WITNESS_BITS))
                )
            if (refuted) null else WitnessProbe(reducedFormula, boolCount, arithmetic, algebraic, work)
                .also {
                    it.failureCapture = failureCapture
                    it.captureSnapshot = progress.searchSnapshot
                }
                .probeWitness(realCount, fixed, work::coupled)
        }
        probeCompleted = true
        if (refuted || witness != null) {
            val checked = witness?.let {
                if (!formulaReduction.requiresOriginalCheck) it else {
                    val point = formulaReduction.reconstruct(it, arithmetic, algebraic)
                    checkNotNull(checkSample(normalized, point, boolCount, arithmetic, algebraic))
                }
            }
            arithmetic.step()
            yield(
                if (refuted) {
                    QfNraResult.Unsatisfiable(work.snapshot())
                } else {
                    QfNraResult.Satisfiable(checkNotNull(checked), work.snapshot())
                },
            )
            return@sequence
        }
        yield(null)
        work.equationalRootLevels = work.equationalRootLevels ?: 0
        work.booleanTheoryRegions = work.booleanTheoryRegions ?: 0
        work.booleanTheoryAtoms = work.booleanTheoryAtoms ?: 0
        work.booleanTheoryConstantRefutations = work.booleanTheoryConstantRefutations ?: 0
        work.booleanTheoryResourceRefusals = work.booleanTheoryResourceRefusals ?: 0
        val regions = reducedFormula.booleanRegions(
            arithmetic,
            progress,
            onPartialCheck = failureCapture?.let { capture ->
                { owner, premises ->
                    capture.signCheck(owner, premises, progress.searchSnapshot, work.spentWork, arithmetic)
                }
            },
            onConflict = {
                failureCapture?.signConflict(it, progress.searchSnapshot, work.spentWork, arithmetic)
                partialConflictObserver?.let { observer -> arithmetic.execution.handoff { observer(it) } }
            },
            coupled = coupledSections,
            onCoupledConflict = { conflict ->
                coupledConflictObserver?.let { observer -> arithmetic.execution.handoff { observer(conflict) } }
            },
        )
        var incomplete: PolynomialResource? = null
        while (true) {
            failureCapture?.operation(
                "boolean-region",
                progress.searchSnapshot,
                progress.regions,
                work.spentWork,
                arithmetic,
            )
            if (!regions.hasNext()) break
            val region = regions.next()
            if (region == null) {
                yield(null)
                continue
            }
            progress.enteredRegion(work.spentWork)
            val count = checkNotNull(work.booleanTheoryRegions)
            work.booleanTheoryRegions = count + minOf(1L, Long.MAX_VALUE - count)
            val atoms = checkNotNull(work.booleanTheoryAtoms)
            work.booleanTheoryAtoms = atoms + minOf(region.atoms.size.toLong(), Long.MAX_VALUE - atoms)
            try {
                val regionalAtoms = region.atoms.map { (id, truth) ->
                    arithmetic.step()
                    reducedFormula.atoms[id].let { if (truth) it else it.complemented() }
                }
                val regionalLp = PolynomialLpRelaxation.prepare(regionalAtoms, realCount, arithmetic, work)
                val infeasible = regionalLp?.use {
                    val solved = it.solve()
                    work.envelopeQueries++
                    arithmetic.step()
                    solved?.verdict == LpVerdict.INFEASIBLE
                } == true
                if (infeasible) {
                    work.envelopeRefutations++
                    continue
                }
                if (!regionFeasible(reducedFormula, region, emptyMap(), arithmetic, algebraic)) {
                    val constants = checkNotNull(work.booleanTheoryConstantRefutations)
                    work.booleanTheoryConstantRefutations = constants + minOf(1L, Long.MAX_VALUE - constants)
                    continue
                }
                failureCapture?.context(formulaReduction, arithmetic, region)
                failureCapture?.operation(
                    "regional-reduction",
                    progress.searchSnapshot,
                    progress.regions,
                    work.spentWork,
                    arithmetic,
                )
                val reducing = PolynomialAffineReduction.prepare(reducedFormula, region, realCount, arithmetic) {
                    val sections = checkNotNull(work.equationalSections)
                    work.equationalSections = sections + minOf(1L, Long.MAX_VALUE - sections)
                }
                var reduction: PolynomialAffineReduction? = null
                while (reduction == null) {
                    reduction = reducing.next()
                    if (reduction == null) yield(null)
                }
                if (reduction.inconsistent) continue
                failureCapture?.context(formulaReduction, arithmetic, region, reduction)
                val cellRegion = cellRefinement?.region(reducedFormula, region, reduction)
                val feedbackScope = cellFeedback?.candidateScope(reducedFormula, region, cellRegion?.scope, arithmetic)
                val numericFeedback: ((PolynomialAtom, Map<Int, AlgebraicReal>) -> Boolean)? =
                    feedbackScope?.let { scope ->
                        val available = { cellRefinement?.cells.orEmpty() }
                        return@let { atom, point ->
                            cellFeedback?.candidateRejection(atom, scope, point, available) != null
                        }
                    }
                reducedRegionObserver?.let { observer -> arithmetic.execution.handoff { observer(reduction.atoms) } }
                val activeFormula = if (reduction.changed) {
                    PolynomialFormula.conjunction(reduction.atoms, arithmetic)
                } else reducedFormula
                val activeRegion = if (reduction.changed) {
                    PolynomialBooleanRegion(activeFormula.atoms.indices.associateWith {
                        arithmetic.step()
                        true
                    }, region.bools)
                } else region
                val nonzero = ArrayList<Polynomial>()
                val polynomials = activeRegion.atoms.map { (index, truth) ->
                    arithmetic.step()
                    val atom = activeFormula.atoms[index]
                    if (atom.holds(0) != truth) {
                        nonzero.add(atom.polynomial)
                    }
                    atom.polynomial
                }
                val equations = activeRegion.atoms.mapNotNull { (index, truth) ->
                    arithmetic.step()
                    val atom = activeFormula.atoms[index]
                    if ((atom.relation == PolynomialRelation.EQ && truth) ||
                        (atom.relation == PolynomialRelation.NE && !truth)
                    ) atom.polynomial else null
                }
                val order = polynomialVariableOrder(polynomials, realCount, arithmetic)
                val ordered = ArrayList<Polynomial>()
                val distinct = HashSet<Polynomial>()
                for (polynomial in polynomials) {
                    arithmetic.step()
                    if (distinct.add(polynomial)) ordered.add(polynomial)
                }
                val family = LinkedHashSet<Polynomial>()
                var included = 0
                var finishProjection = false
                var complete: Boolean
                do {
                    arithmetic.step()
                    if (finishProjection) {
                        while (included < ordered.size) {
                            arithmetic.step()
                            family.add(ordered[included++])
                        }
                    } else if (included < ordered.size) {
                        family.add(ordered[included++])
                    }
                    complete = included == ordered.size
                    try {
                        val selected = family.map {
                            arithmetic.step()
                            it
                        }
                        val required = equations.filter {
                            arithmetic.step()
                            it in family
                        }
                        val exclusions = nonzero.filter {
                            arithmetic.step()
                            it in family
                        }
                        failureCapture?.operation(
                            "projection", progress.searchSnapshot, progress.regions,
                            work.spentWork, arithmetic, selected, zeroes = required, nonzero = exclusions,
                            completeFamily = complete,
                        )
                        val pending = PolynomialProjection.prepare(
                            selected,
                            realCount,
                            arithmetic,
                            required,
                            order,
                            nonzeroPolynomials = exclusions,
                            onElimination = { variable, operands, root ->
                                failureCapture?.operation(
                                    "elimination", progress.searchSnapshot, progress.regions,
                                    work.spentWork, arithmetic, operands, variable, root, completeFamily = complete,
                                )
                                projectionObserver?.let { observer ->
                                    arithmetic.execution.handoff { observer(variable, operands, root) }
                                }
                            },
                            onRootSection = {
                                val roots = checkNotNull(work.equationalRootLevels)
                                work.equationalRootLevels = roots + minOf(1L, Long.MAX_VALUE - roots)
                            },
                        ) {
                            val sections = checkNotNull(work.equationalSections)
                            work.equationalSections = sections + minOf(1L, Long.MAX_VALUE - sections)
                        }
                        var projection: PolynomialProjection? = null
                        while (projection == null) {
                            projection = pending.next()
                            if (projection == null) yield(null)
                        }
                        if (projection.inconsistentEquations) break
                        work.projectedPolynomials += minOf(
                            projection.polynomialCount,
                            Long.MAX_VALUE - work.projectedPolynomials,
                        )
                        yield(null)
                        failureCapture?.operation(
                            "lifting",
                            progress.searchSnapshot,
                            progress.regions,
                            work.spentWork,
                            arithmetic,
                            selected,
                            completeFamily = complete,
                        )
                        val assignment = searchCells(activeFormula, activeRegion, projection, boolCount,
                            arithmetic, algebraic, work, fixed, if (complete) null else family,
                            failureCapture, progress.searchSnapshot, cellRegion, numericFeedback)
                        if (assignment != null) {
                            val regionChecked = if (!reduction.changed) assignment else {
                                val point = reduction.reconstruct(assignment, arithmetic, algebraic)
                                checkRegionSample(reducedFormula, region, point, boolCount, arithmetic, algebraic)
                            }
                            val checked = if (!formulaReduction.requiresOriginalCheck) regionChecked else {
                                val point = formulaReduction.reconstruct(regionChecked, arithmetic, algebraic)
                                checkNotNull(checkSample(normalized, point, boolCount, arithmetic, algebraic))
                            }
                            arithmetic.step()
                            yield(QfNraResult.Satisfiable(checked, work.snapshot()))
                            return@sequence
                        }
                    } catch (stop: PolynomialResourceLimit) {
                        failureCapture?.refused(stop.resource.name, arithmetic)
                        if (complete) throw stop
                        // A larger required family can admit a section that avoids the refused projection.
                        finishProjection = true
                    }
                    // A partial projection's rejected samples do not exhaust the source region.
                    yield(null)
                } while (!complete)
            } catch (stop: PolynomialResourceLimit) {
                failureCapture?.refused(stop.resource.name, arithmetic)
                // An incomplete region can be skipped for SAT search, but withholds source exhaustion.
                if (incomplete == null) incomplete = stop.resource
                val refusals = checkNotNull(work.booleanTheoryResourceRefusals)
                work.booleanTheoryResourceRefusals = refusals + minOf(1L, Long.MAX_VALUE - refusals)
                work.lastBooleanTheoryResource = stop.resource
                yield(null)
            }
        }
        arithmetic.step()
        yield(
            if (incomplete == null) {
                QfNraResult.Unsatisfiable(work.snapshot())
            } else {
                QfNraResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), incomplete)
            },
        )
    }.iterator().also { cellFeedback?.replaceSnapshot(formulaReduction.formula, arithmetic) }

    fun next(): QfNraResult? {
        result?.let { return it }
        var entered = false
        val next = try {
            arithmetic.execution.operation {
                entered = true
                val initial = firstAdvance
                firstAdvance = false
                val started = work.spentWork
                val snapshot = progress.snapshots
                val probeFinished = probeCompleted
                var next = preparation.next()
                var pauses = 1
                while (!initial && next == null && progress.snapshots == snapshot && probeCompleted == probeFinished &&
                    pauses < 8192 && work.spentWork - started < 32768
                ) {
                    next = preparation.next()
                    pauses++
                }
                next
            }
        } catch (_: PolynomialInterrupted) {
            if (!entered) failureCapture?.restoreCursor(PolynomialFailureCursorState(emptyMap(), emptyMap()))
            QfNraResult.Unknown(
                if (work.limitReached) QfNraReason.WorkLimit else QfNraReason.Interrupted,
                work.snapshot(),
            )
        }
        return when (next) {
            is QfNraResult.Satisfiable -> next.copy(stats = work.snapshot())
            is QfNraResult.Unsatisfiable -> next.copy(stats = work.snapshot())
            is QfNraResult.Unknown -> next.copy(stats = work.snapshot())
            null -> null
        }.also {
            result = it
            if (it != null) close()
        }
    }
}

@Suppress("TooGenericExceptionCaught") // cleanup must retain the primary arithmetic or observer failure
internal class QfNraWork(params: QfNraParams) : ArithmeticBudget(params.cancellation, params.maximumWork) {
    var envelopeQueries: Long = 0
    var envelopeRefutations: Long = 0
    var envelopeRefinements: Long = 0
    var projectedPolynomials: Long = 0
    var liftedCells: Long = 0
    var dyadicAttempts: Long = 0
    var dyadicBases: Long = 0
    var dyadicPreExtraction = false
    var booleanTheoryRegions: Long? = null
    var booleanTheoryAtoms: Long? = null
    var booleanTheoryConstantRefutations: Long? = null
    var booleanTheoryResourceRefusals: Long? = null
    var lastBooleanTheoryResource: PolynomialResource? = null
    var equationalSections: Long? = null
    var equationalRootLevels: Long? = null
    private var coupledWorkDeclines: Long = 0
    private var dyadicPreExtractionDeclines: Long = 0
    val spentWork: Long get() = spent
    private var probeEnd: Long? = null
    private var coupledEnd: Long? = null
    private var probeStart: Long = 0
    private var probeSpent: Long = 0
    private var probeParentStopped = false
    override fun isCancelled(): Boolean {
        val stopped = super.isCancelled()
        if (probeEnd != null && stopped) probeParentStopped = true
        return stopped || probeEnd?.let { spent >= it } == true || coupledEnd?.let { spent >= it } == true
    }
    override val exhausted get() = limitReached || probeEnd?.let { spent >= it } == true ||
        coupledEnd?.let { spent >= it } == true
    override val reservationLimit get() =
        (minOf(probeEnd ?: Long.MAX_VALUE, coupledEnd ?: Long.MAX_VALUE) - spent).coerceAtLeast(0L)
    fun probe(block: () -> QfNraAssignment?): QfNraAssignment? {
        execution?.flush()
        check(probeEnd == null)
        val remaining = (MAX_WITNESS_WORK - probeSpent).coerceAtLeast(0)
        if (remaining == 0L) return null
        probeStart = spent
        probeEnd = spent + minOf(remaining, Long.MAX_VALUE - spent)
        probeParentStopped = false
        var failure: Throwable? = null
        return try {
            block()
        } catch (stop: PolynomialInterrupted) {
            // An enclosing stop remains terminal even if its predicate later clears.
            if (probeParentStopped) {
                failure = stop
                throw stop
            }
            null
        } catch (_: PolynomialResourceLimit) {
            null
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            preservingFailure(failure) {
                try {
                    execution?.flush()
                } finally {
                    probeSpent += minOf(spent - probeStart, Long.MAX_VALUE - probeSpent)
                    probeEnd = null
                }
                if (failure == null && !probeParentStopped) execution?.resume()
            }
        }
    }
    fun coupled(block: () -> QfNraAssignment?): QfNraAssignment? {
        execution?.flush()
        check(probeEnd != null && coupledEnd == null)
        coupledEnd = probeStart + minOf(MAX_WITNESS_WORK / 2, Long.MAX_VALUE - probeStart)
        var failure: Throwable? = null
        return try {
            block()
        } catch (stop: PolynomialInterrupted) {
            if (probeParentStopped) {
                failure = stop
                throw stop
            }
            if (coupledEnd?.let { spent >= it } == true) {
                coupledWorkDeclines += minOf(1L, Long.MAX_VALUE - coupledWorkDeclines)
                if (dyadicPreExtraction) {
                    dyadicPreExtractionDeclines += minOf(1L, Long.MAX_VALUE - dyadicPreExtractionDeclines)
                }
            }
            null
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            preservingFailure(failure) {
                try {
                    execution?.flush()
                } finally {
                    coupledEnd = null
                    dyadicPreExtraction = false
                }
                if (failure == null && !probeParentStopped) execution?.resume()
            }
        }
    }
    fun snapshot(): QfNraStats = QfNraStats(projectedPolynomials, liftedCells, spent,
        dyadicAttempts, dyadicBases, coupledWorkDeclines, dyadicPreExtractionDeclines,
        booleanTheoryRegions, booleanTheoryAtoms, booleanTheoryConstantRefutations,
        booleanTheoryResourceRefusals, lastBooleanTheoryResource, equationalSections, equationalRootLevels,
        envelopeQueries, envelopeRefutations, envelopeRefinements)
}

@Suppress("TooGenericExceptionCaught") // settlement retains the primary observer or arithmetic failure
private inline fun preservingFailure(primary: Throwable?, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (failure: Throwable) {
        if (primary == null) throw failure
        if (failure !== primary) primary.addSuppressed(failure)
    }
}
