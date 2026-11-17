package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.saturatedAdd
import com.eignex.util.numbers.times
import kotlin.time.Duration
import kotlin.time.TimeSource

private class RefinedCandidate(
    var point: ExactLpWitness? = null,
    var bound: CertifiedLpBound? = null,
    var dual: List<BigFraction>? = null,
    var conflict: BigRationalConflict? = null,
    var support: LpExactSupport? = null,
    var basis: Basis? = null,
    var usedBasis: Boolean = false,
    var luAttempted: Boolean = false,
    var pointUsesBasis: Boolean = false,
    var boundUsesBasis: Boolean = false,
    var conflictUsesBasis: Boolean = false,
    var dualUsesBasis: Boolean = false,
) {
    val attained: Boolean get() = point != null && bound?.value == point?.objective
    fun acceptPoint(value: ExactLpWitness, basis: Boolean) {
        val current = point
        if (current == null || value.objective < current.objective ||
            (value.objective == current.objective && pointUsesBasis && !basis)
        ) {
            point = value
            pointUsesBasis = basis
        }
    }

    fun acceptBound(value: CertifiedLpBound, basis: Boolean) {
        val current = bound
        if (current == null || value.value > current.value ||
            (value.value == current.value && boundUsesBasis && !basis)
        ) {
            bound = value
            boundUsesBasis = basis
        }
    }

    fun accept(checked: ReconstructedCertificate, basis: Boolean = false, pointBasis: Boolean = basis) {
        checked.witness?.let { acceptPoint(it, pointBasis) }
        checked.bound?.let { acceptBound(it, basis) }
        if (checked.conflict != null) {
            conflict = checked.conflict
            support = checked.conflictSupport
            conflictUsesBasis = basis
        }
        val success = checked.witness != null || checked.bound != null || checked.conflict != null
        usedBasis = usedBasis || (basis && success) || (pointBasis && checked.witness != null)
    }
}

private class RefinementRun(
    private val request: LpRefinementRequest,
    private val meter: RefinementMeter,
    private val sourceModel: LpModel,
    private val sourceFactors: ExactBasisCache,
) {
    private val source by lazy { RefinementAuthority(requireNotNull(sourceModel.exactState), meter) }
    val candidate = RefinedCandidate()
    var unboundedness: ExactLpUnboundedness? = null
    var unboundednessUsesBasis = false
    var sourceSingularBasis: Basis? = null

    fun run(
        primal: DoubleArray?,
        duals: DoubleArray?,
        basis: Basis?,
        known: ExactLpWitness?,
        needPoint: Boolean,
        hint: DoubleArray?,
        reconstructInitial: Boolean,
        preferBasis: Boolean,
        feasibleOnly: Boolean,
    ) {
        candidate.basis = basis
        if (preferBasis && basis != null && sourceModel.m <= meter.basisLimits().factor.dimension) {
            meter.diagnostic?.stage = LpRefinementStage.SOURCE_BASIS
            checkBasis(sourceModel, basis, sourceFactors, candidate, primalOnly = feasibleOnly)
            meter.diagnostic?.stage = LpRefinementStage.RUN
            if (candidate.attained || candidate.conflict != null || (feasibleOnly && candidate.point != null)) return
        }
        if (known != null) {
            meter.diagnostic?.stage = LpRefinementStage.KNOWN_POINT
            candidate.accept(check(source, known.primal, null, null, false))
            meter.diagnostic?.stage = LpRefinementStage.RUN
        }
        if (feasibleOnly) {
            if (candidate.point == null && needPoint && meter.limits.maxAuxiliaries > 0) {
                if (sourceModel.hasStrictSides(meter)) strictFeasibility() else feasibility()
            }
            return
        }
        if (candidate.point == null && needPoint && meter.limits.maxAuxiliaries > 0 &&
            sourceModel.hasStrictSides(meter)
        ) {
            strictFeasibility()
            return
        }
        if (primal != null && duals != null && meter.limits.maxRounds > 0) {
            improve(source, primal, duals, basis, null, candidate, sourceFactors, reconstructInitial)
        }
        if (candidate.attained || candidate.conflict != null) return
        // A finite lower bound rules out recession but says nothing about source feasibility.
        var direction = if (candidate.bound == null) hint?.let { reconstructedDirection(it) } else null
        var directionUsesBasis = false
        if (direction == null && candidate.bound == null && basis != null) {
            direction = directRay(source, basis, sourceFactors)
            directionUsesBasis = direction != null
            candidate.usedBasis = candidate.usedBasis || directionUsesBasis
        }
        if (candidate.point == null && needPoint && meter.metrics.auxiliaries < meter.limits.maxAuxiliaries) {
            feasibility()
        }
        if (candidate.conflict != null || candidate.bound != null) return
        if (direction == null && candidate.point != null && meter.metrics.auxiliaries < meter.limits.maxAuxiliaries) {
            recession()?.let {
                direction = it.first
                directionUsesBasis = it.second
            }
        }
        val point = candidate.point
        val ray = direction
        if (point != null && ray != null) {
            unboundedness = exactRecession(source, point, ray, meter)
            if (unboundedness != null) {
                unboundednessUsesBasis = candidate.pointUsesBasis || directionUsesBasis
                meter.metrics = meter.metrics.copy(rays = meter.metrics.rays + 1)
            }
        }
        // Without a float optimum, an exact point is still a seed, whether the caller supplied it or the feasibility
        // pass above found it: a float solve rounding the source's data can call a feasible model infeasible, and only
        // rounds corrected around that point reach its optimum.
        if (primal == null && point != null && unboundedness == null && meter.limits.maxRounds > 0) {
            improve(source, null, DoubleArray(source.source.m), null, null, candidate, sourceFactors)
        }
    }

    private fun check(
        a: RefinementAuthority,
        x: List<BigFraction>?,
        y: List<BigFraction>?,
        basis: Basis?,
        reconstruct: Boolean,
    ): ReconstructedCertificate {
        meter.poll()
        val checked = if (reconstruct) {
            reconstructRationalCertificate(
                a.model,
                x,
                y,
                basis,
                cancellation = meter.token,
                limits = meter.proofLimits(),
            )
        } else {
            verifyRationalCertificate(
                a.model,
                x,
                y,
                basis = basis,
                cancellation = meter.token,
                limits = meter.proofLimits(),
            )
        }
        meter.record(checked)
        return checked
    }

    private fun improve(
        a: RefinementAuthority,
        primal: DoubleArray?,
        duals: DoubleArray,
        proposed: Basis?,
        anchor: LpWorkingScope?,
        output: RefinedCandidate,
        factors: ExactBasisCache,
        reconstructInitial: Boolean = true,
        sufficient: (RefinedCandidate) -> Boolean = { it.attained || it.conflict != null },
    ) {
        if (duals.size != a.source.m) meter.stop(LpRefinementDecline.CANDIDATE)
        val pointUsesBasis = output.point != null && output.pointUsesBasis
        val knownPoint = output.point?.let { a.fullPoint(it.primal) }
        var x = knownPoint ?: a.seed(requireNotNull(primal), proposed)
        var y = duals.map { meter.number(BigFraction.ofDouble(it) ?: meter.stop(LpRefinementDecline.PROJECTION)) }
        var basis = proposed?.takeIf(a::validBasis)
        output.basis = basis
        output.accept(
            check(a, if (knownPoint == null) a.sourcePoint(x) else null, y, basis, reconstructInitial),
            pointBasis = pointUsesBasis,
        )
        output.dual = y
        output.dualUsesBasis = false
        if (sufficient(output) || meter.limits.maxRounds == 0) return
        var residual = a.residual(x, y, basis, 0, 0)
        var nextReconstruction = 1
        var stalls = 0
        withChild(residual.working, anchor) { scope, firstAllowance ->
            for (round in 1..(meter.limits.maxRounds - meter.metrics.rounds).coerceAtLeast(0)) {
                meter.poll()
                if (round > 1 && !scope.replaceState(residual.working)) {
                    meter.poll()
                    meter.stop(LpRefinementDecline.ADOPTION)
                }
                val attempt = numerical(scope, if (round == 1) basis else null, firstAllowance, round == 1)
                val float = attempt?.second ?: run {
                    meter.poll()
                    val reason = when (scope.lastFloatTermination) {
                        LpFloatTermination.WORK -> LpRefinementDecline.WORK
                        LpFloatTermination.PIVOTS -> LpRefinementDecline.PIVOTS
                        LpFloatTermination.CANCELLED -> LpRefinementDecline.CANCELLED
                        else -> if (attempt == null) LpRefinementDecline.ADOPTION else LpRefinementDecline.NUMERICAL
                    }
                    meter.stop(reason)
                }
                val correction = RefinementAuthority(scope.state, meter)
                val dx = correction.seed(float.primal, float.basis)
                x = x.indices.map { meter.add(x[it], meter.multiply(dx[it], residual.primalScale.reciprocal())) }
                y = y.indices.map {
                    meter.add(
                        y[it],
                        meter.multiply(
                            meter.number(
                                BigFraction.ofDouble(float.duals[it])
                                    ?: meter.stop(LpRefinementDecline.PROJECTION),
                            ),
                            residual.dualScale.reciprocal(),
                        ),
                    )
                }
                basis = float.basis
                output.basis = basis
                output.dual = y
                output.dualUsesBasis = false
                meter.metrics = meter.metrics.copy(rounds = meter.metrics.rounds + 1)
                if (attempt.first.lastPivots == 0) {
                    stalls++
                    meter.metrics = meter.metrics.copy(stalls = meter.metrics.stalls + 1)
                } else {
                    stalls = 0
                }
                val reconstruct = round >= nextReconstruction
                if (knownPoint != null) meter.charge(x.size.toLong())
                val primalCandidate = if (x == knownPoint) null else a.sourcePoint(x)
                output.accept(check(a, primalCandidate, y, basis, reconstruct), pointBasis = pointUsesBasis)
                if (reconstruct) nextReconstruction = nextReconstructionRound(round)
                if (sufficient(output)) break
                if (stalls >= 2) {
                    if (!output.luAttempted) exactCandidate(a, requireNotNull(basis), factors, output)
                    break
                }
                residual = a.residual(x, y, basis, residual.primalExponent, residual.dualExponent)
            }
        }
    }

    private fun numerical(
        scope: LpWorkingScope,
        warm: Basis?,
        firstAllowance: LpFloatAllowance,
        first: Boolean,
    ): Pair<LpSolver, FloatLpResult?>? {
        val before = scope.metrics
        val allowance = if (first) firstAllowance else meter.allowance(false)
        return try {
            scope.solveFloat(warm, if (warm != null) null else allowance)
        } finally {
            val after = scope.metrics
            val preparation = after.owners.preparationWork - before.owners.preparationWork
            val work = after.solves.workOps - before.solves.workOps
            val pivots = after.solves.pivots - before.solves.pivots
            meter.completed(saturatedAdd(preparation, work), pivots = pivots)
            meter.metrics = meter.metrics.copy(
                floatWork = saturatedAdd(meter.metrics.floatWork, work),
                preparationWork = saturatedAdd(meter.metrics.preparationWork, preparation),
                floatFactors = meter.metrics.floatFactors + floatFactorCount(
                    after.solves,
                ) - floatFactorCount(before.solves),
                observedPivots = meter.metrics.observedPivots + pivots,
            )
        }
    }

    private fun <T> withChild(
        working: LpWorkingModel,
        anchor: LpWorkingScope?,
        block: (LpWorkingScope, LpFloatAllowance) -> T,
    ): T {
        val previousStage = meter.diagnostic?.stage
        meter.diagnostic?.stage = LpRefinementStage.CHILD_ADMISSION
        val allowance = meter.allowance(true)
        previousStage?.let { meter.diagnostic.stage = it }
        return if (anchor == null) {
            request.source.withWorkingModel(working, meter.token, allowance) { block(it, allowance) }
        } else {
            anchor.withWorkingModel(working, allowance) { block(it, allowance) }
        }
    }

    private fun exactCandidate(
        a: RefinementAuthority,
        basis: Basis,
        cache: ExactBasisCache,
        output: RefinedCandidate,
    ) {
        checkBasis(a.model, basis, cache, output)
        if (a === source && output.attained) return
        // The basis check returns no raw BTRAN vector, so the exact duals are solved from the cached factors.
        exactVectors(a, basis, cache, allowBuild = false) { authority, factors, budget ->
            budget.phase = ExactBasisPhase.DUAL
            output.dual = exactSolve(factors, authority.costs, true, budget)
            output.dualUsesBasis = true
        }
    }

    private fun checkBasis(
        model: LpModel,
        basis: Basis,
        cache: ExactBasisCache,
        output: RefinedCandidate,
        primalOnly: Boolean = false,
    ) {
        output.luAttempted = true
        val checked = verifyExactBasis(
            model,
            basis,
            cache = cache,
            cancellation = meter.token,
            limits = meter.basisLimits(),
            primalOnly = primalOnly,
        )
        meter.record(checked.metrics)
        if (checked.singularRank != null) {
            if (model.exactState === sourceModel.exactState) sourceSingularBasis = basis
            output.basis = null
            meter.stop(LpRefinementDecline.CANDIDATE)
        }
        checked.witness?.let { output.acceptPoint(it, true) }
        checked.bound?.let { output.acceptBound(it, true) }
        output.usedBasis = checked.witness != null || checked.bound != null || output.usedBasis
    }

    private fun exactVectors(
        a: RefinementAuthority,
        basis: Basis,
        cache: ExactBasisCache,
        allowBuild: Boolean = true,
        block: (ExactBasisAuthority, RationalBasisFactors, ExactBasisMeter) -> Unit,
    ) {
        val budget = ExactBasisMeter(meter.basisLimits(), meter.token)
        var reason: ExactBasisDecline? = null
        try {
            val authority = ExactBasisAuthority(a.model, basis, budget)
            val cached = cache.find(authority)
            if (cached == null && !allowBuild) return
            val factors = if (cached != null) {
                budget.reuse++
                cached
            } else {
                val matrix = authority.basisMatrix()
                budget.phase = ExactBasisPhase.FACTOR
                val order = cache.proposedOrder(authority)
                budget.factoryCalls++
                val build = RationalBasisFactors.factor(matrix, order, budget.factorLimits(), budget.token)
                budget.record(build.stats)
                when (build) {
                    is RationalBasisBuild.Ready -> build.factors.also { cache.install(authority, it) }
                    is RationalBasisBuild.Singular -> budget.stop(ExactBasisDecline.SINGULAR)
                    is RationalBasisBuild.Declined -> budget.stop(ExactBasisDecline.valueOf(build.reason.name))
                }
            }
            block(authority, factors, budget)
        } catch (stop: ExactBasisStop) {
            reason = stop.reason
        } finally {
            meter.record(budget.snapshot(reason))
        }
    }

    private fun reconstructedDirection(hint: DoubleArray): List<BigFraction>? {
        if (hint.size != source.source.n || hint.any { !it.isFinite() }) return null
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        meter.charge(source.source.keySize, source.source.keySize * 8L)
        val cone = ExactLpModel(
            List(source.source.n) { source.source.entries(it) },
            List(source.source.m) { ExactLpNumber.of(0L) },
            List(source.source.numVars) { j ->
                ExactLpColumn(
                    ExactLpBounds(
                        if (source.bounds[j].lower != null) zero else null,
                        if (source.bounds[j].upper != null) zero else null,
                    ),
                    integral = false,
                )
            },
            List(source.source.m) { source.source.row(it).copy(strict = false) },
            ExactLpObjective(source.costs.map { ExactLpNumber.of(it) }),
        )
        val authority = RefinementAuthority(LpExactState(cone), meter)
        val checked = reconstructCertificate(
            authority.model,
            primal = hint,
            cancellation = meter.token,
            limits = meter.proofLimits(),
        )
        meter.record(checked)
        val point = checked.witness ?: return null
        return authority.fullPoint(point.primal).takeIf { validDirection(source, it, meter) }
    }

    private fun directRay(a: RefinementAuthority, basis: Basis, cache: ExactBasisCache): List<BigFraction>? {
        if (!a.validBasis(basis)) return null
        var attempts = 0
        for (j in basis.status.indices) {
            if (basis.status[j] == VarStatus.BASIC) continue
            val signs = when {
                a.bounds[j].lower != null && a.bounds[j].upper != null -> emptyList()
                a.bounds[j].lower != null -> listOf(BigFraction.ONE)
                a.bounds[j].upper != null -> listOf(BigFraction.MINUS_ONE)
                else -> listOf(BigFraction.ONE, BigFraction.MINUS_ONE)
            }
            for (sign in signs) {
                if (++attempts > 8) return null
                var candidate: List<BigFraction>? = null
                exactVectors(a, basis, cache) { authority, factors, budget ->
                    budget.phase = ExactBasisPhase.RAY
                    val rhs = MutableList(a.source.m) { BigFraction.ZERO }
                    for ((row, coefficient) in authority.matrix[j]) {
                        rhs[row] = budget.subtractProduct(rhs[row], coefficient, sign)
                    }
                    val basics = exactSolve(factors, rhs, false, budget)
                    val ray = MutableList(a.source.numVars) { BigFraction.ZERO }
                    ray[j] = sign
                    for (i in authority.headings.indices) ray[authority.headings[i]] = basics[i]
                    candidate = ray
                }
                // FTRAN spending must be debited before source checking consumes the remainder.
                val ray = candidate ?: return null
                if (validDirection(a, ray, meter)) return ray
            }
        }
        return null
    }

    private fun strictFeasibility() {
        meter.diagnostic?.stage = LpRefinementStage.STRICT_MARGIN
        val started = TimeSource.Monotonic.markNow()
        val before = meter.spentWork
        meter.metrics = meter.metrics.copy(strictAttempts = meter.metrics.strictAttempts + 1)
        try {
            val strict = StrictFeasibility(source)
            meter.metrics = meter.metrics.copy(auxiliaries = meter.metrics.auxiliaries + 1)
            val output = RefinedCandidate()
            withChild(refinementWorking(source.state, strict.model, meter), null) { scope, allowance ->
                val attempt = numerical(scope, null, allowance, true) ?: return@withChild
                val a = RefinementAuthority(scope.state, meter)
                val result = attempt.second
                if (result == null) {
                    val checked = reconstructCertificate(
                        a.model,
                        ray = attempt.first.infeasibleRay,
                        cancellation = meter.token,
                        limits = meter.proofLimits(),
                    )
                    meter.record(checked)
                    output.accept(checked)
                } else {
                    val factors = ExactBasisCache()
                    improve(a, result.primal, result.duals, result.basis, scope, output, factors) {
                        strict.positive(it.point) || it.conflict != null || it.bound?.value?.signum() == 0
                    }
                    if (!strict.positive(output.point) && output.conflict == null) {
                        exactCandidate(a, output.basis ?: result.basis, factors, output)
                    }
                }
            }
            if (strict.positive(output.point)) {
                candidate.accept(
                    check(source, strict.sourcePoint(requireNotNull(output.point)), null, null, false),
                    output.pointUsesBasis,
                )
            }
            if (candidate.point == null) {
                val conflict = output.conflict
                val dual = if (conflict != null) {
                    meter.charge(strict.model.m.toLong(), strict.model.m * 8L)
                    MutableList(strict.model.m) { BigFraction.ZERO }.also { y ->
                        for (index in conflict.rows.indices) {
                            val row = conflict.rows[index]
                            y[row] = meter.add(y[row], conflict.multipliers[index].negated())
                        }
                    }
                } else {
                    output.dual.takeIf { output.bound?.value?.let { it >= BigFraction.ZERO } == true }
                }
                val ray = dual?.let(strict::sourceRay)
                if (ray != null) {
                    val checked = verifyRationalCertificate(
                        source.model,
                        ray = ray,
                        cancellation = meter.token,
                        limits = meter.proofLimits(),
                    )
                    meter.record(checked)
                    candidate.accept(checked, if (conflict != null) output.conflictUsesBasis else output.dualUsesBasis)
                }
            }
        } finally {
            meter.metrics = meter.metrics.copy(
                strictWitnesses = if (candidate.point != null) 1 else 0,
                strictConflicts = if (candidate.conflict != null) 1 else 0,
                strictWork = saturatedAdd(meter.metrics.strictWork, meter.spentWork - before),
                strictElapsed = meter.metrics.strictElapsed + started.elapsedNow(),
            )
        }
    }

    private fun feasibility() {
        val shifts = source.bounds.map { b ->
            when {
                b.lower != null && b.lower.number.value.signum() > 0 -> b.lower.number.value
                b.upper != null && b.upper.number.value.signum() < 0 -> b.upper.number.value
                else -> BigFraction.ZERO
            }
        }
        val activity = source.activity(shifts)
        val h = source.rhs.indices.map { meter.add(source.rhs[it], activity[it].negated()) }
        val auxiliary = auxiliaryModel(source, shifts, h, recession = false)
        val solved = auxiliary(auxiliary)
        val point = solved.point
        if (point != null && point.primal[source.source.numVars] == BigFraction.ONE) {
            val x = source.bounds.indices.map { meter.add(shifts[it], point.primal[it]) }
            val checked = check(source, source.sourcePoint(x), null, null, false)
            candidate.accept(checked, solved.pointUsesBasis)
        }
        if (candidate.point != null) return
        solved.dual?.let { y ->
            val checked = verifyRationalCertificate(
                source.model,
                ray = y,
                cancellation = meter.token,
                limits = meter.proofLimits(),
            )
            meter.record(checked)
            candidate.accept(checked, solved.dualUsesBasis)
        }
    }

    private fun recession(): Pair<List<BigFraction>, Boolean>? {
        val auxiliary = auxiliaryModel(
            source,
            List(source.source.numVars) { BigFraction.ZERO },
            source.rhs.map { BigFraction.ZERO },
            recession = true,
        )
        val solved = auxiliary(auxiliary)
        val point = solved.point ?: return null
        if (point.primal[source.source.numVars] != BigFraction.ONE) return null
        val ray = point.primal.take(source.source.numVars)
        if (!validDirection(source, ray, meter)) return null
        candidate.usedBasis = candidate.usedBasis || solved.pointUsesBasis
        return ray to solved.pointUsesBasis
    }

    private fun auxiliary(model: ExactLpModel): RefinedCandidate {
        meter.metrics = meter.metrics.copy(auxiliaries = meter.metrics.auxiliaries + 1)
        val output = RefinedCandidate()
        withChild(refinementWorking(source.state, model, meter), null) { scope, allowance ->
            val attempt = numerical(scope, null, allowance, true)
            val result = attempt?.second ?: return@withChild
            val a = RefinementAuthority(scope.state, meter)
            val factors = ExactBasisCache()
            improve(a, result.primal, result.duals, result.basis, scope, output, factors)
            if (!output.attained && !output.luAttempted) exactCandidate(a, result.basis, factors, output)
            if (output.dual != null && output.bound?.value?.let { it > BigFraction.MINUS_ONE } == true) {
                // Exact BTRAN can annihilate source free columns that an IEEE dual misses.
                if (!output.luAttempted) exactCandidate(a, output.basis ?: result.basis, factors, output)
            }
        }
        return output
    }
}

private fun floatFactorCount(metrics: LpSolveMetrics): Int = metrics.initialRefactorizations +
    metrics.warmStartRefactorizations + metrics.singularRecoveryRefactorizations + metrics.updateLimitRefactorizations +
    metrics.backendRequestedRefactorizations + metrics.reconcileRecoveryRefactorizations +
    metrics.numericalRecoveryRefactorizations + metrics.primalRefactorizations

private fun exactSolve(
    factors: RationalBasisFactors,
    rhs: List<BigFraction>,
    transpose: Boolean,
    meter: ExactBasisMeter,
): List<BigFraction> {
    meter.solves++
    val solved = factors.solve(rhs, transpose, meter.factorLimits(), meter.token)
    meter.record(solved.stats)
    return when (solved) {
        is RationalBasisSolve.Solved -> solved.values
        is RationalBasisSolve.Declined -> meter.stop(ExactBasisDecline.valueOf(solved.reason.name))
    }
}

private fun auxiliaryModel(
    a: RefinementAuthority,
    shifts: List<BigFraction>,
    h: List<BigFraction>,
    recession: Boolean,
): ExactLpModel {
    val meter = a.meter
    val count = a.source.numVars
    val rows = a.source.m + if (recession) 1 else 0
    if (count.toLong() + 1 + rows > meter.limits.maxCoordinates) meter.stop(LpRefinementDecline.DIMENSION)
    meter.charge(count.toLong() + rows, 128L * (count + 1L + rows))
    val matrix = List(count + 1) { j ->
        val entries = ArrayList<ExactLpEntry>()
        if (j < count) {
            for ((row, coefficient) in a.matrix[j]) entries += ExactLpEntry(row, ExactLpNumber.of(coefficient))
            if (recession && !a.costs[j].isZero) entries += ExactLpEntry(a.source.m, ExactLpNumber.of(a.costs[j]))
        } else if (recession) {
            entries += ExactLpEntry(a.source.m, ExactLpNumber.of(1L))
        } else {
            for (row in h.indices) if (!h[row].isZero) entries += ExactLpEntry(row, ExactLpNumber.of(h[row].negated()))
        }
        meter.charge(entries.size.toLong(), entries.size * 32L)
        entries
    }
    if (matrix.sumOf { it.size.toLong() } + rows > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
    val zero = ExactLpSide(ExactLpNumber.of(0L))
    val columns = List(count + 1 + rows) { j ->
        val bounds = if (j < count) {
            val b = a.bounds[j]
            fun side(value: ExactLpSide?): ExactLpSide? = value?.let {
                if (recession) zero else ExactLpSide(ExactLpNumber.of(meter.add(it.number.value, shifts[j].negated())))
            }
            ExactLpBounds(side(b.lower), side(b.upper))
        } else if (j == count) {
            ExactLpBounds(zero, ExactLpSide(ExactLpNumber.of(1L)))
        } else {
            ExactLpBounds(zero, zero)
        }
        ExactLpColumn(bounds, integral = false)
    }
    return ExactLpModel(
        matrix,
        List(rows) { ExactLpNumber.of(0L) },
        columns,
        List(rows) { ExactLpRow() },
        ExactLpObjective(List(columns.size) { ExactLpNumber.of(if (it == count) -1L else 0L) }),
    )
}

private fun validDirection(a: RefinementAuthority, ray: List<BigFraction>, meter: RefinementMeter): Boolean {
    if (ray.size != a.source.numVars) return false
    var improvement = BigFraction.ZERO
    for (j in ray.indices) {
        val value = meter.number(ray[j])
        if (a.bounds[j].lower != null && value.signum() < 0) return false
        if (a.bounds[j].upper != null && value.signum() > 0) return false
        improvement = meter.add(improvement, meter.multiply(a.costs[j], value))
    }
    return improvement.signum() < 0 && a.activity(ray).all { it.isZero }
}

private fun exactRecession(
    a: RefinementAuthority,
    witness: ExactLpWitness,
    ray: List<BigFraction>,
    meter: RefinementMeter,
): ExactLpUnboundedness? {
    val checked = verifyRationalCertificate(
        a.model,
        sourcePrimal = witness.primal,
        cancellation = meter.token,
        limits = meter.proofLimits(),
    )
    meter.record(checked)
    val point = checked.witness ?: return null
    val full = a.fullPoint(point.primal)
    var denominator = BIG_ONE
    for (j in full.indices) {
        if (a.source.column(j).integral) {
            val coordinate = if (j < a.source.n) point.primal[j] else full[j]
            if (coordinate.den != BIG_ONE) return null
            meter.number(ray[j])
            meter.number(BigFraction.of(denominator, BIG_ONE))
            meter.charge()
            denominator = denominator / denominator.gcd(ray[j].den) * ray[j].den
            meter.number(BigFraction.of(denominator, BIG_ONE))
        }
    }
    val scale = meter.number(BigFraction.of(denominator, BIG_ONE))
    val integralRay = ray.map { meter.multiply(it, scale) }
    if (!validDirection(a, integralRay, meter)) return null
    if (integralRay.indices.any { a.source.column(it).integral && integralRay[it].den != BIG_ONE }) return null
    return ExactLpUnboundedness(point, integralRay.take(a.source.n))
}

// Iterative refinement: each round solves a scaled residual LP in floating point and corrects the exact iterate.
// Gleixner, Steffy and Wolter, "Iterative Refinement for Linear Programming", INFORMS J. Comput. 28(3), 2016,
// https://doi.org/10.1287/ijoc.2016.0692.
@Suppress("TooGenericExceptionCaught")
internal fun refineLp(
    model: LpModel,
    request: LpRefinementRequest,
    primal: DoubleArray? = null,
    duals: DoubleArray? = null,
    basis: Basis? = null,
    witness: ExactLpWitness? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
    directWork: Long = 0L,
    directAllocation: Long = 0L,
    needPoint: Boolean = true,
    direction: DoubleArray? = null,
    directElapsed: Duration = Duration.ZERO,
    reconstructInitial: Boolean = true,
    preferBasis: Boolean = false,
    preferredBasisCache: ExactBasisCache? = null,
    additionalSourceWork: Long = 0L,
    diagnostic: LpRefinementPath? = null,
    claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
): LpRefinementResult {
    val state = model.exactState
    val limits = request.effectiveLimits(additionalSourceWork)
    val cache = request.cache
    val meter = RefinementMeter(limits, cache, cancellation, request.onBasisVerification, diagnostic = diagnostic)
    var run: RefinementRun? = null
    var reason: LpRefinementDecline? = LpRefinementDecline.FAILURE
    var metrics: LpRefinementMetrics
    try {
        if (state == null || request.source.state !== state) meter.stop(LpRefinementDecline.AUTHORITY)
        if (limits.maxRounds == 0 && limits.maxAuxiliaries == 0) meter.stop(LpRefinementDecline.DISABLED)
        if (cache.attempted !== state || limits != cache.lastLimits || claim != cache.lastClaim) {
            cache.attemptedUnseeded = false
            cache.attemptedSeeded = false
        }
        if (if (witness == null) cache.attemptedUnseeded else cache.attemptedSeeded) {
            meter.stop(LpRefinementDecline.REPEATED)
        }
        cache.attempted = state
        cache.lastLimits = limits
        cache.lastClaim = claim
        if (witness == null) cache.attemptedUnseeded = true else cache.attemptedSeeded = true
        diagnostic?.beforePrior = meter.diagnosticBudget()
        diagnostic?.stage = LpRefinementStage.PRIOR
        meter.prior(directWork, directAllocation, directElapsed)
        diagnostic?.afterPrior = meter.diagnosticBudget()
        diagnostic?.stage = LpRefinementStage.ENTRY_CHARGE
        meter.charge()
        diagnostic?.runEntry = meter.diagnosticBudget()
        diagnostic?.stage = LpRefinementStage.RUN
        meter.metrics = meter.metrics.copy(eligible = true)
        val factors = preferredBasisCache?.takeIf { preferBasis && basis != null } ?: ExactBasisCache()
        val current = RefinementRun(request, meter, model, factors)
        run = current
        current.run(primal, duals, basis, witness, needPoint, direction, reconstructInitial, preferBasis,
            claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION)
        diagnostic?.stage = LpRefinementStage.COMPLETION
        meter.poll()
        reason = if (current.candidate.attained || current.candidate.conflict != null ||
            current.unboundedness != null || meter.metrics.strictWitnesses > 0 ||
            ((claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.CONTRADICTION) &&
                current.candidate.point != null)
        ) {
            null
        } else {
            LpRefinementDecline.CANDIDATE
        }
    } catch (stop: RefinementStop) {
        reason = stop.reason
    } finally {
        if (reason == LpRefinementDecline.CANCELLED || cancellation()) {
            reason = LpRefinementDecline.CANCELLED
            run = null
        }
        metrics = meter.finish(reason)
        diagnostic?.decline = reason
    }
    val candidate = run?.candidate
    metrics = metrics.copy(
        witnesses = if (candidate?.point != null) 1 else 0,
        bounds = if (candidate?.bound != null) 1 else 0,
        conflicts = if (candidate?.conflict != null) 1 else 0,
    )
    cache.lastMetrics = metrics
    return LpRefinementResult(
        candidate?.point,
        candidate?.bound,
        candidate?.conflict,
        candidate?.support,
        run?.unboundedness,
        candidate?.usedBasis == true,
        candidate?.pointUsesBasis == true,
        candidate?.boundUsesBasis == true,
        candidate?.conflictUsesBasis == true,
        run?.unboundednessUsesBasis == true,
        candidate?.luAttempted == true,
        run?.sourceSingularBasis,
        metrics,
    )
}
