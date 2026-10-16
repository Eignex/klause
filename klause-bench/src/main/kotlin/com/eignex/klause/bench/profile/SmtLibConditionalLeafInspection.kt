package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UNCHECKED_CAST")
internal class SmtLibConditionalLeafInspection(
    private val definitions: Map<Int, Any>,
    private val sources: Set<Int>,
) {
    private data class Term(val coefficients: Map<Int, Long>, val constant: Long) {
        fun variable(): Int? = if (constant == 0L && coefficients.size == 1 && coefficients.values.single() == 1L) {
            coefficients.keys.single()
        } else null
    }
    private sealed interface Frame {
        class Eval(val term: Term) : Frame
        class Join(val variable: Int, val count: Int) : Frame
    }

    private val supports = HashMap<Int, Set<Term>?>()
    private var visits = 0
    private var stopped = false

    fun inspect(retained: Set<Int>, direct: Set<Int>, rows: List<ReifiedLinear>): JsonObject {
        for (variable in definitions.keys.sorted()) {
            if (stopped) break
            support(variable)
        }
        val leaves = supports.values.filterNotNull().flatMapTo(HashSet()) { it }
        return buildJsonObject {
            put("scope", "read-only conditional branch leaf identities; affine expressions are opaque leaves")
            put("solverCounterChanged", false)
            put("diagnosticVisitLimit", VISIT_LIMIT)
            put("diagnosticVisits", visits)
            put("diagnosticStopped", stopped)
            put("perDefinitionLeafLimit", LEAF_LIMIT)
            put("definitionsObserved", supports.size)
            put("definitionsWithCompleteSupport", supports.values.count { it != null })
            put("largestSupport", supports.values.mapNotNull { it?.size }.maxOrNull())
            put("distinctLeaves", leaves.size)
            put("leafKinds", histogram(leaves.map(::kind)))
            put("retainedSupportSizes", histogram(retained.map { supports[it]?.size?.toString() ?: "unknown" }))
            put("directlyReadDefinitionSupports", histogram(direct.map(::shape)))
            put("externalRowSupports", histogram(rows.map { row ->
                "${row.op}/" + row.vars.filter { it in definitions }.joinToString("+") { shape(it) }
            }))
            put("directDefinitionsWithOnlySourceArithmeticLeaves", direct.count { variable ->
                supports[variable]?.all { leaf -> leaf.coefficients.keys.all { it in sources } } == true
            })
        }
    }

    private fun support(variable: Int): Set<Term>? {
        if (variable in supports) return supports[variable]
        val pending = ArrayDeque<Frame>()
        val results = ArrayList<Set<Term>?>()
        val active = HashSet<Int>()
        pending.addLast(Frame.Eval(Term(mapOf(variable to 1L), 0)))
        while (pending.isNotEmpty()) {
            when (val frame = pending.removeLast()) {
                is Frame.Eval -> {
                    if (++visits > VISIT_LIMIT) {
                        stopped = true
                        return null
                    }
                    val source = frame.term.variable()
                    val definition = source?.let { definitions[it] }
                    when {
                        definition == null -> results.add(setOf(frame.term))
                        source in supports -> results.add(supports[source])
                        !active.add(source!!) -> results.add(null)
                        else -> {
                            val terms = (field(definition, "arms") as List<Any>) +
                                requireNotNull(field(definition, "default"))
                            pending.addLast(Frame.Join(source, terms.size))
                            for (term in terms.asReversed()) {
                                pending.addLast(Frame.Eval(Term(
                                    (field(term, "coeffs") as Map<Int, Long>).toMap(), field(term, "constant") as Long,
                                )))
                            }
                        }
                    }
                }
                is Frame.Join -> {
                    val joined = HashSet<Term>()
                    var complete = true
                    repeat(frame.count) {
                        val child = results.removeAt(results.lastIndex)
                        if (child == null) complete = false
                        else if (complete) {
                            joined += child
                            if (joined.size > LEAF_LIMIT) complete = false
                        }
                    }
                    val result = joined.takeIf { complete }
                    supports[frame.variable] = result
                    active.remove(frame.variable)
                    results.add(result)
                }
            }
        }
        return results.single()
    }

    private fun kind(term: Term): String = when {
        term.coefficients.isEmpty() -> "constant"
        term.coefficients.keys.any { it in definitions } -> "arithmetic-reading-definition"
        term.coefficients.keys.all { it in sources } -> "source-arithmetic"
        else -> "arithmetic-reading-other-auxiliary"
    }

    private fun shape(variable: Int): String {
        val support = supports[variable] ?: return "unknown"
        return listOf("constant", "source-arithmetic", "arithmetic-reading-definition", "arithmetic-reading-other-auxiliary")
            .joinToString("/") { label -> "$label=${support.count { kind(it) == label }}" }
    }

    private fun histogram(values: List<String>): JsonObject = buildJsonObject {
        for ((key, count) in values.groupingBy { it }.eachCount().toSortedMap()) put(key, count)
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private companion object {
        const val VISIT_LIMIT = 65_536
        const val LEAF_LIMIT = 1_024
    }
}
