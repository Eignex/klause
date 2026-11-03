package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.values
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.RootPrimalContinuation
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpEmissionScope
import com.eignex.klause.lp.relaxation.LpEmissionTemplates
import com.eignex.klause.lp.relaxation.LpPrimalTemplates
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.lp.relaxation.checkLpAssembly
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

// Domain values are immutable; references preserve interior holes without expanding a wide interval.
internal class LpPrimalContinuation private constructor(
    private var owner: LpEngine?,
    private var model: LpModel?,
    private var factors: Array<Factor>,
    private var roots: Array<IntDomain>,
    private var live: Array<IntDomain>,
    private var sides: ByteArray,
    private var booleans: ByteArray,
    private val honorsOpenSides: Boolean,
    private val scope: LpEmissionScope,
    private var paidWork: Long,
    private val validationWork: Long,
) : RootPrimalContinuation {
    override fun paidWorkFor(engine: LpEngine): Long = if (owner === engine) paidWork else 0L

    override fun includeSetupWork(engine: LpEngine, work: Long) {
        require(work >= 0L)
        if (owner !== engine) return
        paidWork = if (work > LpEmissionTemplates.MAX_UNITS - paidWork) {
            LpEmissionTemplates.MAX_UNITS + 1L
        } else paidWork + work
    }

    override fun matches(
        engine: LpEngine,
        root: LpRelaxation,
        domains: RelaxationDomains,
        cancellation: Cancellation,
        onWork: (Long) -> Unit,
    ): Boolean {
        if (owner !== engine) return false
        engine.requireOpen()
        val accounting = LpPrimalAssembly(engine, cancellation)
        val started = TimeSource.Monotonic.markNow()
        var outcome = "FAILED"
        try {
            val valid = matchesSource(engine, root, domains, accounting.token)
            outcome = if (valid) "VALIDATED" else "DECLINED"
            return valid
        } catch (cancelled: LpAssemblyCancelled) {
            outcome = "CANCELLED"
            throw cancelled
        } finally {
            paidWork += accounting.work
            onWork(accounting.work)
            engine.sink.lp.observePrimalPhase("PRIMAL_LP_CONTINUATION", outcome,
                started.elapsedNow().inWholeNanoseconds, accounting.work)
            if (outcome != "VALIDATED") close()
        }
    }

    private fun matchesSource(
        engine: LpEngine,
        root: LpRelaxation,
        domains: RelaxationDomains,
        token: Cancellation,
    ): Boolean {
        token.charge(1L)
        token.checkLpAssembly()
        val selection = engine.projections ?: return false
        if (model !== root.model || !scope.matches(selection.lpEmissionNamespace, engine.problem) ||
            honorsOpenSides != domains.honorsOpenSides || factors.size != engine.problem.numFactors ||
            !LpPumpPreparation.hasAffordableShape(engine, root, paidWork + validationWork + 4L)
        ) return false
        for (index in factors.indices) {
            token.charge(1L)
            token.checkLpAssembly()
            if (factors[index] !== engine.problem.factors[index]) return false
        }
        for (variable in roots.indices) {
            token.charge(2L)
            token.checkLpAssembly()
            if (sides[variable] != statedSides(engine, variable) ||
                !sameDomain(roots[variable], engine.problem.rootDomainOf(variable), token) ||
                !sameDomain(live[variable], domains.intDomain(variable), token)
            ) return false
        }
        for (variable in booleans.indices) {
            token.charge(1L)
            token.checkLpAssembly()
            if (booleans[variable] != pin(domains.boolValue(variable))) return false
        }
        token.checkLpAssembly()
        return true
    }

    override fun close() {
        val engine = owner ?: return
        val started = TimeSource.Monotonic.markNow()
        owner = null
        model = null
        factors = emptyArray()
        roots = emptyArray()
        live = emptyArray()
        sides = byteArrayOf()
        booleans = byteArrayOf()
        engine.sink.lp.observePrimalPhase("PRIMAL_LP_CONTINUATION", "RETIRED",
            started.elapsedNow().inWholeNanoseconds)
    }

    companion object {
        fun capture(
            engine: LpEngine,
            root: LpRelaxation,
            domains: RelaxationDomains,
            cancellation: Cancellation,
            setupWork: Long,
            onWork: (Long) -> Unit,
        ): LpPrimalContinuation? {
            if (!engine.reusePumpPreparation || !engine.reusePrimalHandoff) return null
            engine.requireOpen()
            val accounting = LpPrimalAssembly(engine, cancellation)
            val started = TimeSource.Monotonic.markNow()
            var outcome = "FAILED"
            var captured: LpPrimalContinuation? = null
            return try {
                captured = snapshot(engine, root, domains, setupWork, accounting)
                accounting.token.checkLpAssembly()
                outcome = if (captured == null) "DECLINED" else "CAPTURED"
                captured
            } catch (cancelled: LpAssemblyCancelled) {
                outcome = "CANCELLED"
                throw cancelled
            } finally {
                if (outcome != "CAPTURED") captured?.close()
                onWork(accounting.work)
                engine.sink.lp.observePrimalPhase("PRIMAL_LP_CONTINUATION", outcome,
                    started.elapsedNow().inWholeNanoseconds, accounting.work)
            }
        }

        private fun snapshot(
            engine: LpEngine,
            root: LpRelaxation,
            domains: RelaxationDomains,
            setupWork: Long,
            accounting: LpPrimalAssembly,
        ): LpPrimalContinuation? {
            val token = accounting.token
            token.charge(1L)
            token.checkLpAssembly()
            val problem = engine.problem
            val selection = engine.projections ?: return null
            val captureWork = 2L + problem.numFactors + problem.numIntVars * 5L + problem.numBoolVars +
                root.model.n + root.model.m + root.circuitArcs.size
            var validationWork = 1L + problem.numFactors + problem.numIntVars * 2L + problem.numBoolVars
            var units = 64L + LpPrimalTemplates.minimumUnits(problem, root) +
                problem.numIntVars * 19L + problem.numBoolVars + root.hullFactorIds.size +
                root.model.n * 8L + root.circuitArcs.size * 8L +
                root.gatedRows.size * 3L + root.realUpperRows.size * 32L +
                (root.primalDefinition?.storageUnits ?: 0L)
            if (units > LpEmissionTemplates.MAX_UNITS ||
                !LpPumpPreparation.hasAffordableShape(engine, root, setupWork + captureWork + validationWork)
            ) return null
            units = retainedUnits(root, units, token) ?: return null
            // Reserve arrays, their reads, and constant retirement before retaining source values.
            token.charge(1L + problem.numFactors + problem.numIntVars * 5L + problem.numBoolVars)
            token.checkLpAssembly()
            val factors = problem.factors.copyOf()
            val roots = Array(problem.numIntVars) { problem.rootDomainOf(it) }
            val live = Array(problem.numIntVars) { domains.intDomain(it) }
            val sides = ByteArray(problem.numIntVars) { statedSides(engine, it) }
            val booleans = ByteArray(problem.numBoolVars) { pin(domains.boolValue(it)) }
            for (variable in roots.indices) {
                token.checkLpAssembly()
                for (part in 0..1) {
                    val domain = if (part == 0) roots[variable] else live[variable]
                    val count = if (domain.holeCount == 0L) 0L else domain.valueCount
                    if (count > LpEmissionTemplates.MAX_UNITS) return null
                    units += count * 3L
                    validationWork += count * 3L
                    if (units > LpEmissionTemplates.MAX_UNITS ||
                        !LpPumpPreparation.hasAffordableShape(engine, root,
                            setupWork + accounting.work + validationWork)
                    ) return null
                }
            }
            token.checkLpAssembly()
            return LpPrimalContinuation(engine, root.model, factors, roots, live, sides, booleans,
                domains.honorsOpenSides, LpEmissionScope(selection.lpEmissionNamespace, problem),
                accounting.work, validationWork)
        }

        private fun retainedUnits(root: LpRelaxation, initial: Long, token: Cancellation): Long? {
            var units = initial
            for (row in root.model.rowPremises) {
                token.charge(1L)
                token.checkLpAssembly()
                if (row != null) units += 8L + row.vars.size * 4L + row.boolLits.size * 2L
                if (units > LpEmissionTemplates.MAX_UNITS) return null
            }
            for (column in 0 until root.model.n) {
                token.charge(1L)
                token.checkLpAssembly()
                units += (root.colReq[column]?.size ?: 0) +
                    (root.colPresence[column]?.storageUnits ?: 0L) * 32L
                if (units > LpEmissionTemplates.MAX_UNITS) return null
            }
            for (arcs in root.circuitArcs) {
                token.charge(1L)
                token.checkLpAssembly()
                units += arcs.tails.size.toLong() + arcs.heads.size + arcs.cols.size
                if (units > LpEmissionTemplates.MAX_UNITS) return null
            }
            token.checkLpAssembly()
            return units
        }

        private fun sameDomain(expected: IntDomain, current: IntDomain, token: Cancellation): Boolean {
            token.checkLpAssembly()
            if (expected === current) return true
            if (expected.min != current.min || expected.max != current.max) return false
            if (expected.holeCount == 0L && current.holeCount == 0L) return true
            val count = expected.valueCount
            if (count > LpEmissionTemplates.MAX_UNITS || count != current.valueCount) return false
            token.charge(count)
            token.checkLpAssembly()
            val values = LongArray(count.toInt())
            var index = 0
            expected.values.forEach { value ->
                token.charge(1L)
                token.checkLpAssembly()
                values[index++] = value
            }
            index = 0
            var equal = true
            current.values.forEach { value ->
                token.charge(1L)
                token.checkLpAssembly()
                if (values[index++] != value) equal = false
            }
            return equal
        }

        private fun statedSides(engine: LpEngine, variable: Int): Byte =
            ((if (engine.problem.statesLowerBound(variable)) 1 else 0) +
                (if (engine.problem.statesUpperBound(variable)) 2 else 0)).toByte()

        private fun pin(value: Boolean?): Byte = when (value) { true -> 1; false -> -1; null -> 0 }
    }
}
