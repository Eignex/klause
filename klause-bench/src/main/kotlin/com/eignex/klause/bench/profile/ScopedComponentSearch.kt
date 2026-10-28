package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import java.math.BigInteger

internal class ComponentAllowance(
    private val cancellation: Cancellation,
    decisions: Long = 1_000_000L,
    private val maxVisits: Long = 4_000_000L,
    private val maxBytes: Long = 16L * 1024 * 1024,
) {
    val nodes = NodeBudget(decisions)
    var visits = 0L
        private set
    var bytes = 0L
        private set
    val stop = Cancellation { cancellation() || nodes.exhausted() || visits > maxVisits || bytes > maxBytes }

    fun visit(count: Long = 1L): Boolean {
        require(count >= 0L)
        visits = if (count > Long.MAX_VALUE - visits) Long.MAX_VALUE else visits + count
        return !stop()
    }

    fun retain(count: Long): Boolean {
        require(count >= 0L)
        bytes = if (count > Long.MAX_VALUE - bytes) Long.MAX_VALUE else bytes + count
        return !stop()
    }
}

internal data class ComponentSearchResult(
    val children: List<ComponentCostBounds>,
    val closed: Boolean,
    val queries: Int,
    val buildNanos: Long,
    val searchNanos: Long,
    val checkNanos: Long,
)

internal object ScopedComponentSearch {
    fun run(
        scopes: List<ComponentCostScope>,
        fixedAndIsolated: BigInteger,
        cutoff: BigInteger,
        allowance: ComponentAllowance,
        seed: Long,
        lp: Boolean = false,
        initial: List<ComponentCostBounds>? = null,
        restrictionCutoff: BigInteger? = null,
    ): ComponentSearchResult {
        val composition = ScopedComponentBounds(fixedAndIsolated, scopes, restrictionCutoff)
        require(initial == null || initial.map { it.scope } == scopes)
        val bounds = (initial ?: scopes.map { ComponentCostBounds.opening(it) }).toMutableList()
        var queries = 0
        var buildNanos = 0L
        var searchNanos = 0L
        var checkNanos = 0L
        for (i in scopes.indices) {
            if (!allowance.visit(scopes.size.toLong())) break
            if (composition.closes(cutoff, bounds)) break
            val scope = scopes[i]
            var threshold = composition.improvementBudget(i, cutoff, bounds)
            while (allowance.visit()) {
                if (bounds[i].lower >= threshold) break
                val start = System.nanoTime()
                val cells = scope.clauses.sumOf { it.size.toLong() } + scope.weights.size * 4L + 16L
                if (!allowance.retain(cells * 8L) || !allowance.visit(cells)) break
                val factors = ArrayList<Factor>(scope.clauses.size + 1)
                scope.clauses.forEach { factors += Clause(it.toIntArray()) }
                factors += PseudoBoolean(
                    scope.weights.map { parseBigInt(it.toString()) }.toTypedArray(),
                    IntArray(scope.weights.size) { Lit.make(it, true) }, PbOp.LE,
                    parseBigInt((threshold - BigInteger.ONE).toString()),
                )
                val problem = Problem(scope.weights.size, 0, emptyArray(), factors.toTypedArray())
                    .bake(cancellation = allowance.stop)
                val built = System.nanoTime()
                buildNanos += built - start
                if (allowance.stop()) break
                val base = BacktrackPresets.satOptimized(randomSeed = seed, cancellation = allowance.stop)
                val result = BacktrackSolver(problem).solve(base.copy(
                    nodeBudget = allowance.nodes,
                    lpConfig = if (lp) LpConfig(LpEmphasis.DEFAULT) else null,
                ))
                searchNanos += System.nanoTime() - built
                queries++
                val checking = System.nanoTime()
                if (!allowance.visit(cells)) break
                when (result) {
                    is SolveResult.Unsat -> {
                        bounds[i] = bounds[i].refutedBelow(scope, threshold)
                    }
                    is SolveResult.Sat -> {
                        val values = List(scope.weights.size) { result.assignment.boolValue(it) }
                        check(scope.accepts(values) && scope.cost(values) < threshold)
                        bounds[i] = bounds[i].withWitness(values)
                        threshold = checkNotNull(bounds[i].upper)
                    }
                    else -> break
                }
                checkNanos += System.nanoTime() - checking
            }
        }
        val canPublish = allowance.visit(scopes.size.toLong())
        val closed = canPublish && composition.closes(cutoff, bounds)
        return ComponentSearchResult(bounds.toList(), closed, queries, buildNanos, searchNanos, checkNanos)
    }
}
