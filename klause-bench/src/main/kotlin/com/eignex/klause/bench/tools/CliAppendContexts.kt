package com.eignex.klause.bench.tools

internal class CliAppendContexts(private val clock: () -> Long = System::nanoTime) {
    private val started = clock()
    private var inspected = 0
    private var limited = false
    private val cpu = HashMap<String, Long>()
    private val allocation = HashMap<String, Long>()

    val tables: List<Pair<String, Map<String, Long>>>
        get() = listOf(
            "whole CLI: CPU samples by captured append ancestry and context" to cpu.toMap(),
            "whole CLI: sampled allocation weight by captured append ancestry and context (estimated bytes)" to
                allocation.toMap(),
        )

    fun record(frameCount: Int, allocationWeight: Long? = null, stack: () -> List<String>) {
        require(frameCount >= 0 && (allocationWeight == null || allocationWeight >= 0L))
        limited = limited || inspected >= MAX_EVENTS || clock() - started >= MAX_ANALYSIS_NS
        val key = when {
            limited -> "unknown: append analysis limit"
            frameCount > MAX_FRAMES -> "unknown: append stack exceeds $MAX_FRAMES frames"
            else -> {
                val methods = stack()
                require(methods.size == frameCount)
                "${classify(methods)} | ${Profiler.cliContextMarkers(methods)}"
            }
        }
        if (inspected < MAX_EVENTS) inspected++
        val counts = if (allocationWeight == null) cpu else allocation
        counts.merge(key, allocationWeight ?: 1L, Math::addExact)
        check(counts.size <= MAX_GROUPS)
    }

    companion object {
        private const val MAX_EVENTS = 1_000_000
        private const val MAX_ANALYSIS_NS = 10_000_000_000L
        private const val MAX_FRAMES = 256
        private const val MAX_GROUPS = 256
        private const val APPEND = "com.eignex.klause.lp.bounding.LpPropagator.append"
        private const val SYSTEM = "com.eignex.klause.theory.qflra.LiveQfLraSystem."
        private const val COMPONENT = "com.eignex.klause.theory.qflra.ExactLiraSearchComponent."

        internal fun classify(stack: List<String>): String {
            require(stack.size <= MAX_FRAMES)
            val methods = stack.map { it.replace('$', '.') }
            val append = methods.indexOf(APPEND)
            if (append < 0) return "no captured append"
            val definition = methods.indexOf("${SYSTEM}definitionColumn")
            if (definition <= append) return "append: no captured definitionColumn ancestor"
            val terms = methods.indexOf("${SYSTEM}assertTerms")
            val comparison = methods.indexOf("${SYSTEM}assertComparison")
            if (terms <= definition || comparison <= terms) {
                return "definition append: comparison path not captured"
            }
            val parents = methods.drop(comparison + 1)
            if (parents.any { matches(it, "${COMPONENT}assertDisequalities") }) {
                return "comparison append: observed assertDisequalities"
            }
            if (parents.any { matches(it, "${COMPONENT}assertSource") }) {
                return "comparison append: observed assertSource; other ancestors unknown"
            }
            return "comparison append: source assertion ancestor not captured"
        }

        private fun matches(frame: String, method: String): Boolean = frame == method || frame.startsWith("$method.")
    }
}
