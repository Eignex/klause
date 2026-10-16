package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Format
import kotlinx.serialization.Serializable

@Serializable
internal data class CompilationStudyInput(
    val suite: String,
    val problem: String,
    val format: String,
    val sourceHashes: Map<String, String>,
)

@Serializable
internal data class CompilationStudyPlan(
    val mode: String,
    val inputs: List<Int>,
    val repeats: Int,
    val timeout: Long,
    val engines: List<String>,
    val profile: Boolean,
    val params: List<String> = emptyList(),
    val lp: String? = null,
    val exact: Boolean = false,
    val recipe: String? = null,
    val caseSuffix: String? = null,
    val orderOffset: Int = 0,
    val solverSeed: Long = DEFAULT_SEED,
) {
    fun caseName(index: Int, repetition: Int, engine: String, arm: String): String =
        "$mode-${index.toString().padStart(2, '0')}-$repetition-${caseSuffix ?: engine}-$arm"

    fun armOrder(index: Int, repetition: Int): List<String> =
        if ((index.toLong() + repetition + orderOffset) % 2L == 0L) listOf("control", "candidate")
        else listOf("candidate", "control")

    companion object {
        private const val DEFAULT_SEED = 3L
        private const val MAX_INPUTS = 128
        private const val MAX_CASES = 4096L
        private const val MAX_REPEATS = 100
        private const val MAX_TIMEOUT = 3_600_000L
        private val NAME = Regex("[a-z][a-z0-9-]*")
        private val HASH = Regex("[0-9a-f]{64}")

        fun validate(inputs: List<CompilationStudyInput>, plans: List<CompilationStudyPlan>) {
            require(inputs.size in 1..MAX_INPUTS && plans.isNotEmpty())
            require(inputs.map { it.suite to it.problem }.distinct().size == inputs.size)
            for (input in inputs) {
                require(input.suite.isNotBlank() && input.problem.isNotBlank())
                require(input.format in Format.entries.map { it.name })
                require(input.sourceHashes.keys in listOf(setOf("model"), setOf("model", "data")))
                require(input.sourceHashes.values.all { HASH.matches(it) })
            }
            var cases = 0L
            for (plan in plans) {
                require(NAME.matches(plan.mode))
                require(plan.caseSuffix == null || plan.caseSuffix.matches(Regex("[a-z0-9-]+")))
                require(plan.inputs.isNotEmpty() && plan.inputs.distinct().size == plan.inputs.size)
                require(plan.inputs.all { it in inputs.indices })
                require(plan.repeats in 1..MAX_REPEATS && plan.timeout in 1..MAX_TIMEOUT)
                require(plan.engines.isNotEmpty() && plan.engines.distinct().size == plan.engines.size)
                require(plan.engines.all { it in setOf("fixed", "cp", "mixed", "ls") })
                require(plan.params.all { it.matches(Regex("[a-z][a-z0-9-]*=[^\\s]+")) })
                require(plan.params.none { it.startsWith("source-validation=") })
                require(plan.lp == null ||
                    plan.lp.isNotBlank() && plan.lp.none { it.isWhitespace() || it.isISOControl() })
                cases += plan.inputs.size.toLong() * plan.repeats * plan.engines.size * 2
            }
            require(cases in 1..MAX_CASES)
            val names = plans.flatMap { plan ->
                plan.inputs.flatMap { index ->
                    (0 until plan.repeats).flatMap { repetition ->
                        plan.engines.flatMap { engine ->
                            listOf("control", "candidate").map { plan.caseName(index, repetition, engine, it) }
                        }
                    }
                }
            }
            require(names.distinct().size == names.size) { "Study plan overwrites a case" }
        }
    }
}
