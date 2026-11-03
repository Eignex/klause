package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

internal const val INTEGER_LEARNING_ROWS = 64
private const val INTEGER_LEARNING_STEPS = 16
private const val INTEGER_LEARNING_LEVELS = 64

internal class IntegerConflictLearner(private val state: PropagationState) {
    private var selectedRetainedSource = false
    private var combinations = 0
    private var previousSource: IntegerSourceRow? = null
    private var previousUseLo = false
    private var previousRow: IntegerLearningInequality? = null
    private val sourceWork = IntegerSourceWork(state)
    private var previousWideRowSource: IntegerSourceRow? = null
    private var previousWideUseLo = false
    private var previousWideRow: WideIntegerSourceInequality? = null

    fun learn(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? = try {
        selectedRetainedSource = false
        combinations = 0
        derive(factorId, currentLevel, fallback)
    } catch (_: CheckedLongOverflowException) {
        decline("overflow")
    } catch (_: IntegerSourceWorkLimit) {
        decline("source-work-limit")
    }

    private fun retainedDecline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.retained.reject.$cause")
        return null
    }

    private fun decline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.decline.$cause")
        if (selectedRetainedSource) state.arithmeticLearningTrace?.record("integer.retained.decline.$cause")
        return null
    }

    private fun derive(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? {
        val observingStore = state.arithmeticLearningTrace?.collectDeclines == true &&
            (state.learned.integerRows?.isNotEmpty() == true)
        if (observingStore) state.arithmeticLearningTrace?.record("integer.retained.analysis-with-store")
        if (currentLevel !in 1..INTEGER_LEARNING_LEVELS) {
            if (observingStore) state.arithmeticLearningTrace?.record("integer.retained.analysis-level-limit")
            state.arithmeticLearningTrace?.record("integer.level-limit", currentLevel.toLong(), factorId.toLong(),
                state.baseFactorCount.toLong(), fallback.backjumpLevel.toLong(),
                if (fallback.asserting) 1L else 0L, fallback.literals.size.toLong())
            if (state.arithmeticLearningTrace?.collectDeclines == true &&
                factorId in state.baseFactorCount until state.totalFactorCount &&
                state.factorAt(factorId) is LearnedIntegerPropagator) {
                state.arithmeticLearningTrace?.record("integer.retained.seed-level-limit")
            }
            val originalSeed = factorId in 0 until state.baseFactorCount
            return decline(if (originalSeed) "level-limit-original" else "level-limit-learned")
        }
        if (!fallback.asserting) return decline("fallback-nonasserting")
        if (fallback.literals.isEmpty()) return decline("fallback-empty")
        if (fallback.backjumpLevel !in 0 until currentLevel) return decline("fallback-target")
        if ((state.learned.integerRows?.size ?: 0) >= INTEGER_LEARNING_ROWS) return decline("store-capacity")
        val target = fallback.backjumpLevel
        val trail = state.integerLearningTrailAtLevel?.invoke(target) ?: return decline("assertion-unavailable")
        state.arithmeticLearningTrace?.record("integer.fallback-target", target.toLong(), trail.toLong())
        val source = if (factorId in 0 until state.baseFactorCount) sourceFactor(factorId) else null
        val retained = if (source == null) retainedAmoSource(factorId) else null
        if (source == null && retained == null) return decline("seed-source")
        val allowWideSources = source != null && source.linear.vars.size > INTEGER_LEARNING_TERMS
        var retainedSources = if (retained == null) 0 else 1
        if (retained != null) {
            selectedRetainedSource = true
            state.integerChainedSources++
            state.arithmeticLearningTrace?.record("integer.retained-seed", factorId.toLong())
        }
        var row: IntegerLearningInequality = if (source == null) requireNotNull(retained) else when (source.linear.op) {
            LinearOp.LE -> normalizedSource(source, useLo = true)
            LinearOp.GE -> normalizedSource(source, useLo = false)
            LinearOp.EQ -> {
                val lower = normalizedSource(source, useLo = true) ?: return decline("seed-normalization")
                if (lower.minimum(state) > lower.bound) lower else normalizedSource(source, useLo = false)
            }
            LinearOp.NE -> null
        } ?: return decline("seed-normalization")
        state.arithmeticLearningTrace?.row("integer.seed-row", row, state)
        if (row.minimum(state) <= row.bound) return decline("seed-not-conflicting")
        val used = mutableSetOf<PivotKey>()
        var transientSteps = 0
        var nonOwnerSource = false
        var nonOwnerOriginalSource = false
        var slackOriginalSource = false
        var duplicateContinuation = false
        repeat(INTEGER_LEARNING_STEPS) {
            if (combinations == INTEGER_LEARNING_STEPS) return decline("combination-limit")
            state.checkFiniteWork()
            val event = reason(row, used, allowWideSources, target) ?: return decline(
                if (combinations == INTEGER_LEARNING_STEPS) "combination-limit" else "no-qualified-endpoint")
            used.add(event.key)
            if (event.key.retained) {
                selectedRetainedSource = true
                retainedSources++
                state.integerChainedSources++
                if (event.nonOwnerSource) {
                    nonOwnerSource = true
                    state.integerNonOwnerSources++
                }
                state.arithmeticLearningTrace?.row("integer.retained-parent-row", event.row, state)
            } else state.arithmeticLearningTrace?.row("integer.pivot-row", event.row, state, event.trail)
            if (event.nonOwnerOriginalSource) {
                nonOwnerOriginalSource = true
                state.integerNonOwnerOriginalSources++
            }
            if (event.slackOriginalSource) {
                slackOriginalSource = true
                state.integerSlackOriginalSources++
            }
            val combined = event.combination ?: combine(row, event.row, event.variable,
                allowWideSources, allowCompactGrowth = retainedSources > 0)
            combined ?: return decline("combination-shape-or-guard")
            row = combined
            if (combined.minimum(state) <= combined.bound) return decline("combination-lost-conflict")
            val compact = if (combined is IntegerConflictRow) combined else {
                transientSteps++
                state.arithmeticLearningTrace?.record(if (retainedSources == 0)
                    "integer.retained.box.original-only-attempt" else "integer.retained.box.owned-attempt")
                state.arithmeticLearningTrace?.record(if (nonOwnerOriginalSource)
                    "integer.retained.box.late-attempt" else "integer.retained.box.early-attempt")
                val proposal = (combined as? TransientIntegerRow)?.declaredBoundConsequence(state) ?: return@repeat
                val modes = proposal.declaredBoundModes or (if (!nonOwnerOriginalSource) DECLARED_BOUND_EARLY else 0)
                state.integerDeclaredBoundTerms += proposal.declaredBoundTerms
                if (modes != 0) state.integerDeclaredBoundExtendedTerms += proposal.declaredBoundTerms
                state.arithmeticLearningTrace?.record("integer.retained.box.proposal")
                proposal.derivedFromDeclaredBounds(proposal.declaredBoundTerms, modes)
            }
            if (state.learned.integerRows?.any { it.row.subsumes(compact) } == true) {
                if (compact !== combined) {
                    state.arithmeticLearningTrace?.record("integer.retained.box.reject.duplicate")
                    return@repeat
                }
                if (retainedSources > 0) {
                    duplicateContinuation = true
                    state.integerDuplicateContinuationSteps++
                    state.arithmeticLearningTrace?.record("integer.retained.duplicate.continued")
                    return@repeat
                }
                return decline("duplicate")
            }
            val assertion = compact.assertionAt(state, trail)
            val asserts = assertion == IntegerAssertion.ASSERTING
            state.arithmeticLearningTrace?.record("integer.assertion", target.toLong(), trail.toLong(),
                if (asserts) 1 else 0)
            if (!asserts) {
                if (compact !== combined) {
                    state.arithmeticLearningTrace?.record("integer.retained.box.reject.nonasserting")
                    if (state.arithmeticLearningTrace?.collectDeclines == true) {
                        state.arithmeticLearningTrace?.record("integer.retained.box.compact." + assertion.diagnostic)
                        val temporaryAssertion = combined.assertionAt(state, trail)
                        state.arithmeticLearningTrace?.record(
                            "integer.retained.box.temporary." + temporaryAssertion.diagnostic)
                    }
                }
                return@repeat
            }
            val levels = compact.variables.indices.map { index ->
                val variable = compact.variables[index]
                val lower = compact.coefficients[index] > 0L
                val domain = state.intDomains[variable]
                state.boundEstablishmentLevel(variable, if (lower) domain.min else domain.max, lower) ?: 0
            }.plus(fallback.decisionLevels.toList()).distinct().sorted().toIntArray()
            if (retainedSources > 0) {
                state.integerChainedProduced++
                if (transientSteps > 0) state.integerChainedTransientProduced++
                if (nonOwnerSource) state.integerNonOwnerProduced++
                if (nonOwnerOriginalSource) state.integerNonOwnerOriginalProduced++
                if (slackOriginalSource) state.integerSlackOriginalProduced++
                if (duplicateContinuation) state.integerDuplicateContinuationProduced++
            }
            if (compact.declaredBoundTerms > 0) state.integerDeclaredBoundProduced++
            if (compact.declaredBoundModes != 0) state.integerDeclaredBoundExtendedProduced++
            return ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(
                compact.derivedThroughTransient(transientSteps)
                    .derivedFromRetainedSources(retainedSources, nonOwnerSource, nonOwnerOriginalSource,
                        slackOriginalSource, duplicateContinuation),
                target, levels.size, levels, fallback)
        }
        return decline("step-limit-without-assertion")
    }

    private fun combine(
        row: IntegerLearningInequality,
        parent: IntegerLearningInequality,
        variable: Int,
        allowWideSources: Boolean,
        allowCompactGrowth: Boolean,
    ): IntegerLearningInequality? {
        if (combinations == INTEGER_LEARNING_STEPS) return null
        combinations++
        return if (allowWideSources || allowCompactGrowth) {
            row.resolveTransient(parent, variable, sourceWork, allowCompactGrowth)
        } else row.resolve(parent, variable, sourceWork)
    }

    private fun slackCombination(
        row: IntegerLearningInequality,
        parent: IntegerLearningInequality,
        variable: Int,
    ): IntegerLearningInequality? {
        state.arithmeticLearningTrace?.record("integer.retained.original.slack.trial")
        return try {
            val combined = combine(row, parent, variable, allowWideSources = true, allowCompactGrowth = true)
                ?: return null
            if (chargedMinimum(combined) <= combined.bound) {
                originalDecline("slack-not-conflicting")
            } else combined
        } catch (_: CheckedLongOverflowException) {
            originalDecline("slack-overflow")
        }
    }

    private fun normalizedSource(source: IntegerSourceRow, useLo: Boolean): IntegerLearningInequality? {
        if (source.linear.vars.size > INTEGER_LEARNING_TERMS) {
            sourceWork.visit()
            if (previousWideRowSource?.linear === source.linear && previousWideRowSource?.guard == source.guard &&
                previousWideUseLo == useLo) return previousWideRow
            val row = WideIntegerSourceInequality.create(source, useLo, sourceWork)
            previousWideRowSource = source
            previousWideUseLo = useLo
            previousWideRow = row
            return row
        }
        val previous = previousSource
        if (previous != null && previous.linear === source.linear && previous.guard == source.guard &&
            previousUseLo == useLo) {
            return previousRow
        }
        val row = source.row(useLo)
        previousSource = source
        previousUseLo = useLo
        previousRow = row
        return row
    }

    private fun sourceFactor(
        factorId: Int,
        trail: Int = state.undo.size,
        forBoundReason: Boolean = false,
        allowWideSources: Boolean = true,
    ): IntegerSourceRow? = GuardedIntegerSource(state).source(factorId, trail, forBoundReason,
        if (allowWideSources) sourceWork else null)

    private fun reason(
        row: IntegerLearningInequality,
        used: Set<PivotKey>,
        allowWideSources: Boolean,
        target: Int,
    ): BoundEvent? {
        sourceWork.visit()
        if (state.learned.integerRows?.isNotEmpty() == true) {
            retainedReason(row, used)?.let {
                state.arithmeticLearningTrace?.record("integer.retained.preferred")
                return it
            }
        }
        var candidates: IntArray? = null
        var count = 0
        var ownedEndpoint = false
        for (index in row.variables.indices) {
            row.visit(state)
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            if (state.arithmeticLearningTrace?.collectDeclines == true &&
                factorId in state.baseFactorCount until state.totalFactorCount &&
                state.factorAt(factorId) is LearnedIntegerPropagator) {
                ownedEndpoint = true
                state.arithmeticLearningTrace?.record("integer.retained.endpoint")
            }
            val marker = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
            state.arithmeticLearningTrace?.marker(variable, lower, marker)
            if (!isLazyReason(marker)) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.eager-or-decision")
                continue
            }
            marker ?: continue
            if (marker.size != LAZY_PAYLOAD + 6 || marker[1] != factorId) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.marker-shape")
                continue
            }
            val key = PivotKey(variable, marker[2], lower, retained = false)
            if (key in used) continue
            val indices = candidates ?: IntArray(row.variables.size).also { candidates = it }
            var position = count
            while (position > 0) {
                row.visit(state)
                val previous = checkNotNull(endpointMarker(row, indices[position - 1]))
                if (previous[2] >= marker[2]) break
                indices[position] = indices[position - 1]
                position--
            }
            indices[position] = index
            count++
        }
        val indices = candidates
        if (indices != null) for (position in 0 until count) {
            row.visit(state)
            qualifiedReason(row, indices[position], allowWideSources)?.let {
                if (ownedEndpoint) state.arithmeticLearningTrace?.record("integer.retained.original-preferred")
                return it
            }
        }
        val scanned = scannedReason(row, used, target, allowWideSources)
        if (scanned == null && selectedRetainedSource && combinations < INTEGER_LEARNING_STEPS) {
            observeEagerReasons(row, target)
        }
        return scanned
    }

    private fun observeEagerReasons(row: IntegerLearningInequality, target: Int) {
        val trace = state.arithmeticLearningTrace?.takeIf { it.collectDeclines } ?: return
        for (index in row.variables.indices) {
            sourceWork.visit()
            if (endpointLevel(row, index) <= target) continue
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val kind = state.classifyIntegerEagerReason(variable, lower, sourceWork)
            trace.record("integer.retained.eager." + kind.diagnostic)
            val owner = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            val ownerKind = when {
                owner !in 0 until state.totalFactorCount -> "unavailable"
                state.retiredLearnedFactor(owner) -> "retired"
                owner < state.baseFactorCount -> when (state.problem.factors[owner]) {
                    is Table -> "table"
                    is Linear, is ExactLinear -> "linear"
                    is ReifiedLinear -> "guarded-linear"
                    else -> "other-original"
                }
                state.factorAt(owner) is LearnedIntegerPropagator -> "integer"
                state.factorAt(owner) is ClausePropagator -> "clause"
                else -> "other-learned"
            }
            trace.record("integer.retained.eager-owner." + ownerKind)
        }
    }

    private fun retainedAmoSource(factorId: Int): IntegerConflictRow? {
        sourceWork.visit()
        if (state.incremental) return retainedDecline("incremental")
        if (factorId !in state.baseFactorCount until state.totalFactorCount) return retainedDecline("not-learned")
        val propagator = state.factorAt(factorId) as? LearnedIntegerPropagator
            ?: return retainedDecline("not-owned")
        return retainedAmoSource(propagator)
    }

    private fun retainedAmoSource(propagator: LearnedIntegerPropagator): IntegerConflictRow? {
        sourceWork.visit()
        if (state.incremental) return retainedDecline("incremental")
        val cache = state.learned.integerRows ?: return retainedDecline("no-store")
        val slot = propagator.retentionSlot
        if (slot !in cache.indices || cache[slot] !== propagator) return retainedDecline("identity")
        val row = propagator.row
        if (row.guard != Lit.NONE) return retainedDecline("guard")
        if (row.variables.size < 2) return retainedDecline("width")
        var negative = 0L
        val bounds = state.problem.intBounds
        for (index in row.variables.indices) {
            sourceWork.visit()
            val coefficient = row.coefficients[index]
            val variable = row.variables[index]
            if ((coefficient != 1L && coefficient != -1L) || !bounds.hasLower(variable) ||
                !bounds.hasUpper(variable) || bounds.lower(variable) != 0L || bounds.upper(variable) != 1L) {
                return retainedDecline("declared-unit-binary")
            }
            if (coefficient < 0L) negative++
        }
        if (row.bound != 1L - negative) return retainedDecline("cardinality")
        state.arithmeticLearningTrace?.record("integer.retained.qualified")
        return row
    }

    private fun retainedReason(row: IntegerLearningInequality, used: Set<PivotKey>): BoundEvent? {
        for (index in row.variables.indices) {
            sourceWork.visit()
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            val key = PivotKey(variable, factorId, lower, retained = true)
            if (key in used) continue
            val parent = retainedAmoSource(factorId) ?: continue
            var coefficient = 0L
            for (parentIndex in parent.variables.indices) {
                sourceWork.visit()
                if (parent.variables[parentIndex] == variable) {
                    coefficient = parent.coefficients[parentIndex]
                    break
                }
            }
            if (coefficient == 0L || (coefficient > 0L) == lower) {
                state.arithmeticLearningTrace?.record("integer.retained.reject.pivot-sign")
                continue
            }
            // The owned inequality is a source in this state; the reason id only selects it.
            // No eager support is treated as a historical lazy endpoint.
            return BoundEvent(parent, variable, state.undo.size, key)
        }
        return null
    }

    private fun scannedReason(
        row: IntegerLearningInequality,
        used: Set<PivotKey>,
        target: Int,
        allowWideSources: Boolean,
    ): BoundEvent? {
        val cache = state.learned.integerRows ?: return null
        state.arithmeticLearningTrace?.record("integer.retained.scan")
        val candidates = IntArray(row.variables.size)
        var count = 0
        for (index in row.variables.indices) {
            sourceWork.visit()
            val level = endpointLevel(row, index)
            if (level <= target) continue
            var position = count
            while (position > 0) {
                sourceWork.visit()
                if (endpointLevel(row, candidates[position - 1]) >= level) break
                candidates[position] = candidates[position - 1]
                position--
            }
            candidates[position] = index
            count++
        }
        if (count == 0) state.arithmeticLearningTrace?.record("integer.retained.scan.target-level")
        for (position in 0 until count) {
            val index = candidates[position]
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val owner = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            for (slot in cache.indices) {
                sourceWork.visit()
                val propagator = cache[slot]
                if (owner in state.baseFactorCount until state.totalFactorCount &&
                    state.factorAt(owner) === propagator) continue
                // Cache slots are stable during one analysis and cannot alias factor ids.
                val key = PivotKey(variable, -slot - 1, lower, retained = true)
                if (key in used) continue
                val parent = oppositeTightParent(propagator, variable, lower) ?: continue
                state.arithmeticLearningTrace?.record("integer.retained.non-owner")
                return BoundEvent(parent, variable, state.undo.size, key, nonOwnerSource = true)
            }
        }
        if (!selectedRetainedSource) return null
        val originalCandidates = candidates.copyOf(count)
        return originalReason(row, used, originalCandidates, allowWideSources, slackOnly = false)
            ?: originalReason(row, used, originalCandidates, allowWideSources, slackOnly = true)
    }

    private fun originalReason(
        row: IntegerLearningInequality,
        used: Set<PivotKey>,
        candidates: IntArray,
        allowWideSources: Boolean,
        slackOnly: Boolean,
    ): BoundEvent? {
        if (slackOnly) state.arithmeticLearningTrace?.record("integer.retained.original.slack.scan")
        for (index in candidates) {
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val owner = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            val occurrences = state.projection.readyIntOccurrences(variable)
            state.arithmeticLearningTrace?.record(
                if (occurrences == null) "integer.retained.original.unindexed" else "integer.retained.original.indexed")
            val size = occurrences?.size ?: state.baseFactorCount
            for (offset in 0 until size) {
                if (combinations == INTEGER_LEARNING_STEPS) return null
                sourceWork.visit()
                val factorId = occurrences?.get(offset) ?: offset
                if (factorId !in 0 until state.baseFactorCount || factorId == owner) continue
                // Negative original ids cannot alias historical trail positions.
                val key = PivotKey(variable, -factorId - 1, lower, retained = false)
                if (key in used) continue
                val parent = originalParent(factorId, variable, lower, allowWideSources, slackOnly) ?: continue
                val combination = if (slackOnly) slackCombination(row, parent, variable) ?: continue else null
                state.arithmeticLearningTrace?.record("integer.retained.original.selected")
                if (slackOnly) state.arithmeticLearningTrace?.record("integer.retained.original.slack.selected")
                return BoundEvent(parent, variable, state.undo.size, key, nonOwnerOriginalSource = true,
                    slackOriginalSource = slackOnly, combination = combination)
            }
        }
        return null
    }

    private fun originalDecline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.retained.original.reject.$cause")
        return null
    }

    private fun originalParent(
        factorId: Int,
        variable: Int,
        lower: Boolean,
        allowWideSources: Boolean,
        slackOnly: Boolean,
    ): IntegerLearningInequality? {
        val size = when (val factor = state.problem.factors[factorId]) {
            is Linear -> factor.vars.size
            is ExactLinear -> factor.size
            is ReifiedLinear -> return originalDecline("guarded")
            else -> return originalDecline("source-kind")
        }
        val limit = if (allowWideSources) INTEGER_SOURCE_TERMS else INTEGER_LEARNING_TERMS
        if (size > limit) return originalDecline("term-limit")
        // Wide extraction reserves its own visits; compact projection and normalization need funding too.
        if (size <= INTEGER_LEARNING_TERMS) repeat(size * 4 + 8) { sourceWork.visit() }
        val source = sourceFactor(factorId, forBoundReason = true, allowWideSources = allowWideSources)
            ?: return originalDecline("source-qualification")
        if (source.guard != Lit.NONE) return originalDecline("guarded")
        val useLo = source.linear.op != LinearOp.GE
        var parent = normalizedSource(source, useLo) ?: return originalDecline("normalization")
        var coefficient = chargedCoefficient(parent, variable)
        if (coefficient == 0L) return originalDecline("no-pivot")
        if ((coefficient > 0L) == lower && source.linear.op == LinearOp.EQ) {
            if (size <= INTEGER_LEARNING_TERMS) repeat(size * 2 + 4) { sourceWork.visit() }
            parent = normalizedSource(source, !useLo) ?: return originalDecline("normalization")
            coefficient = chargedCoefficient(parent, variable)
        }
        if ((coefficient > 0L) == lower) return originalDecline("pivot-sign")
        val minimum = chargedMinimum(parent)
        if (slackOnly) {
            if (minimum >= parent.bound) return originalDecline("not-slack")
        } else if (minimum != parent.bound) return originalDecline("not-tight")
        return parent
    }

    private fun chargedMinimum(row: IntegerLearningInequality): Long {
        var minimum = 0L
        for (index in row.variables.indices) {
            sourceWork.visit()
            val domain = state.intDomains[row.variables[index]]
            val endpoint = if (row.coefficients[index] > 0L) domain.min else domain.max
            minimum = addExact(minimum, mulExact(row.coefficients[index], endpoint))
        }
        return minimum
    }

    private fun chargedCoefficient(row: IntegerLearningInequality, variable: Int): Long {
        for (index in row.variables.indices) {
            sourceWork.visit()
            if (row.variables[index] == variable) return row.coefficients[index]
        }
        return 0L
    }

    private fun endpointLevel(row: IntegerLearningInequality, index: Int): Int {
        val variable = row.variables[index]
        return if (row.coefficients[index] > 0L) state.intMinLevel[variable] else state.intMaxLevel[variable]
    }

    private fun oppositeTightParent(
        propagator: LearnedIntegerPropagator,
        variable: Int,
        lower: Boolean,
    ): IntegerConflictRow? {
        val parent = retainedAmoSource(propagator) ?: return null
        var coefficient = 0L
        for (index in parent.variables.indices) {
            sourceWork.visit()
            if (parent.variables[index] == variable) {
                coefficient = parent.coefficients[index]
                break
            }
        }
        if (coefficient == 0L) return retainedDecline("scan-no-pivot")
        if ((coefficient > 0L) == lower) return retainedDecline("scan-pivot-sign")
        var minimum = 0L
        for (index in parent.variables.indices) {
            sourceWork.visit()
            val domain = state.intDomains[parent.variables[index]]
            val endpoint = if (parent.coefficients[index] > 0L) domain.min else domain.max
            minimum = addExact(minimum, mulExact(parent.coefficients[index], endpoint))
        }
        if (minimum != parent.bound) return retainedDecline("not-tight")
        return parent
    }

    private fun endpointMarker(row: IntegerLearningInequality, index: Int): IntArray? {
        val variable = row.variables[index]
        return if (row.coefficients[index] > 0L) state.intMinAntecedents[variable]
        else state.intMaxAntecedents[variable]
    }

    private fun qualifiedReason(
        row: IntegerLearningInequality,
        index: Int,
        allowWideSources: Boolean,
    ): BoundEvent? {
        val variable = row.variables[index]
        val lower = row.coefficients[index] > 0L
        val marker = checkNotNull(endpointMarker(row, index))
        val factorId = marker[1]
        val source = sourceFactor(
            factorId, marker[2], forBoundReason = true, allowWideSources = allowWideSources,
        )
        if (source == null) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.source", factorId.toLong())
            return null
        }
        val extra = marker[LAZY_PAYLOAD + 5] != 0
        if (extra != (source.guard != Lit.NONE) ||
            (extra && marker[LAZY_PAYLOAD + 4] != (source.guard xor 1))) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.guard-marker", source.guard.toLong())
            return null
        }
        val position = marker[LAZY_PAYLOAD]
        if (source.linear.vars.getOrNull(position) != variable) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.source-position")
            return null
        }
        val useLo = marker[LAZY_PAYLOAD + 1] == 1
        if ((source.linear.op == LinearOp.LE && !useLo) || (source.linear.op == LinearOp.GE && useLo)) return null
        val sourceRow = normalizedSource(source, useLo) ?: return null
        if ((sourceRow.coefficient(variable) > 0L) == lower) return null
        state.arithmeticLearningTrace?.row("integer.endpoint-row", sourceRow, state, marker[2])
        val proposed = sourceRow.boundFor(state, variable, marker[2])
        if (proposed == null) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.unavailable-bound")
            return null
        }
        if (!state.rawIntegerEndpointSupported(variable, lower, proposed, marker[2])) {
            val domain = state.intDomains[variable]
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.raw-endpoint-provenance", proposed,
                domain.min, domain.max, marker[2].toLong())
            return null
        }
        return BoundEvent(sourceRow, variable, marker[2], PivotKey(variable, marker[2], lower, retained = false))
    }

    private data class PivotKey(val variable: Int, val source: Int, val lower: Boolean, val retained: Boolean)

    private class BoundEvent(
        val row: IntegerLearningInequality,
        val variable: Int,
        val trail: Int,
        val key: PivotKey,
        val nonOwnerSource: Boolean = false,
        val nonOwnerOriginalSource: Boolean = false,
        val slackOriginalSource: Boolean = false,
        val combination: IntegerLearningInequality? = null,
    )
}
