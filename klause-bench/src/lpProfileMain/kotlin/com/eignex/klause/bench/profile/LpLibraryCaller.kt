package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource.Monotonic

internal fun libraryCaller(options: Map<String, String>) {
    require(options.keys == setOf("mode", "source64", "seed", "timeout-ms"))
    val timeout = options.getValue("timeout-ms").toLong().also { require(it in 1..300_000) }
    val seed = options.getValue("seed").toLong()
    val started = Monotonic.markNow()
    val stop = Cancellation.after(timeout.milliseconds)
    val compiled = Mps.parse(Base64.decode(options.getValue("source64")).decodeToString()).toExactSourceProblem()
    require(compiled.sourceExact) { "Library caller requires an exact original-source working view" }
    val sourceObjective = requireNotNull(compiled.objective).toLinearObjective()
    val objective = if (compiled.maximize) sourceObjective.negated() else sourceObjective
    val solver = BacktrackSolver(compiled.model.bake(stop))
    val controls = BacktrackPresets.satOptimized(randomSeed = seed, cancellation = stop)
        .copy(maxInstructions = 1000L)
    val result = solver.minimize(objective, controls)
    val witness = result.assignment?.let { sample ->
        val coordinates: List<BigFraction> = compiled.columns.map { column ->
            if (column.real) {
                sample.exactReals?.get(column.id) ?: requireNotNull(BigFraction.ofDouble(sample.reals[column.id]))
            } else {
                sample.exactInts?.get(column.id)?.let { BigFraction.of(it, BIG_ONE) }
                    ?: BigFraction.ofLong(sample.ints[column.id])
            }
        }
        compiled.sourceWitness(coordinates, stop).also { require(it.exact) }
    }
    println("c libraryCaller=BacktrackSolver(baked).minimize")
    println("c libraryControls=satOptimized maxInstructions=1000 seed=$seed timeoutMs=$timeout")
    println("c libraryScope=original-source parse, exact lowering, bake, production solver, source check, render")
    println("c cliRoutingEquivalence=false")
    println("c sourceExact=${compiled.sourceExact}")
    println("c kotlinVersion=${KotlinVersion.CURRENT}")
    println("c nativeResult=${result::class.simpleName}")
    for ((phase, value) in result.stats.lp.phases) {
        println("c lpPhase_${phase}_Calls=${value.calls}")
        println("c lpPhase_${phase}_Nanos=${value.nanos}")
        println("c lpPhase_${phase}_Work=${value.work}")
        for ((outcome, count) in value.outcomes) println("c lpPhase_${phase}_$outcome=$count")
    }
    for ((phase, value) in result.stats.lp.continuation.work) println("c lpContinuation_${phase}_Work=$value")
    for ((phase, value) in result.stats.lp.continuation.allocation) {
        println("c lpContinuation_${phase}_Allocation=$value")
    }
    if (witness == null) {
        println("s UNKNOWN")
    } else {
        println("o ${witness.objective}")
        println("s SATISFIABLE")
        println(
            compiled.columns.zip(witness.values).joinToString(" ", prefix = "v ") { (column, value) ->
            "${column.name}=$value"
        }
        )
    }
    println("c libraryElapsedNs=${started.elapsedNow().inWholeNanoseconds}")
}
