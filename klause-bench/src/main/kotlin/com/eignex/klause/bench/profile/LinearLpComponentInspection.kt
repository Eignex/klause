package com.eignex.klause.bench.profile

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearLpComponentInspection {
    fun inspect(theory: Any?, terminal: OpenTheoryResult?, advanced: Boolean, out: File) {
        val started = System.nanoTime()
        val stop = Cancellation.after(5.seconds)
        val result = buildJsonObject {
            put("scope", "retained-exact-lp-structural-components")
            put("sourceVerdictClaim", false)
            put("cliRoutingEquivalence", "unverified")
            put("traversalAdvanced", advanced)
            put("solveDeadlineRenewed", false)
            put("structuralInspectionBudgetMs", 5000)
            put("rawTerminal", terminal?.javaClass?.simpleName)
            put("rawTerminalReason", (terminal as? OpenTheoryResult.Unknown)?.reason?.name)
            val lazy = theory?.javaClass?.getDeclaredField("lpDelegate")?.apply { isAccessible = true }
                ?.get(theory) as? Lazy<*>
            val lp = lazy?.takeIf { it.isInitialized() }?.value
            val state = lp?.let { member(it, "getState") }
            if (state == null) {
                put("complete", false)
                put("decline", "retained-lp-state-unavailable")
            } else {
                val captured = try {
                    snapshot(state, stop, out)
                } catch (_: InspectionStopped) {
                    declined("structural-deadline")
                }
                put("state", captured)
            }
            put("elapsedNs", System.nanoTime() - started)
        }
        out.resolve("lp-components.json").writeText(result.toString() + "\n")
        println(result)
    }

    private fun snapshot(state: Any, stop: Cancellation, out: File): JsonObject {
        val model = requireNotNull(member(state, "getModel"))
        val columns = member(model, "getN") as Int
        val rowCount = member(model, "getM") as Int
        if (columns > MAX_DIMENSION || rowCount > MAX_DIMENSION) return declined("dimension-limit")
        val rows = Array(rowCount) { ArrayList<Int>() }
        val entries = model.javaClass.getMethod("entries", Int::class.javaPrimitiveType)
        var count = 0
        var zeros = 0
        for (column in 0 until columns) {
            if (stop()) return declined("structural-deadline")
            for (entry in entries.invoke(model, column) as List<*>) {
                if (++count > MAX_ENTRIES) return declined("entry-limit")
                if (count % 1024 == 0 && stop()) return declined("structural-deadline")
                val item = requireNotNull(entry)
                if (number(requireNotNull(member(item, "getNumber"))).isZero) zeros++ else {
                    rows[member(item, "getRow") as Int].add(column)
                }
            }
        }
        return describe(state, model, columns, rows.map { it.toIntArray() }, zeros, stop, out)
    }

    private fun describe(
        state: Any, model: Any, columns: Int, rows: List<IntArray>, zeros: Int, stop: Cancellation, out: File,
    ): JsonObject {
        val identities = requireNotNull(member(state, "getRows"))
        val row = identities.javaClass.getMethod("row", Int::class.javaPrimitiveType)
        val column = model.javaClass.getMethod("column", Int::class.javaPrimitiveType)
        val objective = requireNotNull(member(model, "getObjective"))
        val cost = objective.javaClass.getMethod("cost", Int::class.javaPrimitiveType)
        val active = BooleanArray(rows.size)
        val constrained = BooleanArray(rows.size)
        var freeLogicalCosts = 0
        for (index in rows.indices) {
            if (stop()) return declined("structural-deadline")
            active[index] = member(row.invoke(identities, index), "getActive") as Boolean
            val bounds = requireNotNull(member(column.invoke(model, columns + index), "getBounds"))
            constrained[index] = member(bounds, "getLower") != null || member(bounds, "getUpper") != null
            if (!constrained[index] && !number(cost.invoke(objective, columns + index)).isZero) freeLogicalCosts++
        }
        val union = LinearLpGraph.partition(columns, rows, BooleanArray(rows.size) { true }, stop)
            ?: return declined("structural-deadline")
        val bounded = LinearLpGraph.partition(columns, rows, constrained, stop)
            ?: return declined("structural-deadline")
        if (stop()) return declined("structural-deadline")
        val raw = buildJsonObject {
            put("structuralColumns", columns)
            put("rows", buildJsonArray {
                rows.forEachIndexed { index, entries ->
                    if (stop()) throw InspectionStopped()
                    add(buildJsonObject {
                        put("active", active[index])
                        put("logicalBoundPresent", constrained[index])
                        put("columns", buildJsonArray {
                            entries.forEachIndexed { offset, entry ->
                                if (offset % 1024 == 0 && stop()) throw InspectionStopped()
                                add(JsonPrimitive(entry))
                            }
                        })
                    })
                }
            })
        }
        out.resolve("lp-graph.json").writeText(raw.toString() + "\n")
        return buildJsonObject {
            put("complete", true)
            put("structuralColumns", columns)
            put("retainedRows", rows.size)
            put("nonzeroEntries", rows.sumOf { it.size })
            put("storedZeroEntriesExcluded", zeros)
            put("activeRowIdentities", active.count { it })
            put("boundedLogicalRows", constrained.count { it })
            put("freeLogicalNonzeroCosts", freeLogicalCosts)
            put("matrixRevision", member(state, "getMatrixRevision") as Long)
            put("boundRevision", member(state, "getBoundRevision") as Long)
            put("rowRevision", member(state, "getRowRevision") as Long)
            put("union", union.json())
            put("boundedLogicalRowsOnly", bounded.json())
            put("rowRemovalAuthorized", false)
        }
    }

    private fun declined(reason: String) = buildJsonObject {
        put("complete", false)
        put("decline", reason)
    }

    private fun number(value: Any): BigFraction = member(value, "getValue") as BigFraction

    private fun member(owner: Any, name: String): Any? = owner.javaClass.getMethod(name).invoke(owner)

    private class InspectionStopped : RuntimeException()

    private const val MAX_DIMENSION = 100_000
    private const val MAX_ENTRIES = 1_000_000
}

internal data class LinearLpBlock(val columns: Int, val rows: Int)

internal data class LinearLpPartition(val blocks: List<LinearLpBlock>, val isolatedColumns: Int, val emptyRows: Int) {
    fun json() = buildJsonObject {
        put("rowCoupledBlocks", blocks.size)
        put("isolatedColumns", isolatedColumns)
        put("emptyRows", emptyRows)
        put("blocks", buildJsonArray {
            blocks.forEach { block ->
                add(buildJsonObject {
                    put("columns", block.columns)
                    put("rows", block.rows)
                })
            }
        })
    }
}

internal object LinearLpGraph {
    fun partition(
        columns: Int, rows: List<IntArray>, included: BooleanArray, stop: Cancellation = Cancellation.Never,
    ): LinearLpPartition? {
        require(columns >= 0 && rows.size == included.size)
        val parent = IntArray(columns) { it }
        val rank = IntArray(columns)
        val used = BooleanArray(columns)
        fun root(column: Int): Int {
            var current = column
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        var empty = 0
        var visited = 0
        for ((index, row) in rows.withIndex()) {
            if (stop()) return null
            if (!included[index]) continue
            if (row.isEmpty()) {
                empty++
                continue
            }
            for (column in row) {
                require(column in 0 until columns)
                if (++visited % 1024 == 0 && stop()) return null
                used[column] = true
                var first = root(row[0])
                var second = root(column)
                if (first == second) continue
                if (rank[first] < rank[second]) {
                    val swap = first
                    first = second
                    second = swap
                }
                parent[second] = first
                if (rank[first] == rank[second]) rank[first]++
            }
        }
        val blocks = LinkedHashMap<Int, IntArray>()
        for (column in 0 until columns) {
            if (column % 1024 == 0 && stop()) return null
            if (used[column]) blocks.getOrPut(root(column)) { IntArray(2) }[0]++
        }
        for ((index, row) in rows.withIndex()) {
            if (stop()) return null
            if (included[index] && row.isNotEmpty()) blocks.getValue(root(row[0]))[1]++
        }
        if (stop()) return null
        return LinearLpPartition(blocks.values.map { LinearLpBlock(it[0], it[1]) }, used.count { !it }, empty)
    }
}
