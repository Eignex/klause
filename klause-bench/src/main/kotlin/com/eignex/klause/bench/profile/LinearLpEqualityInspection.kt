package com.eignex.klause.bench.profile

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.seconds

internal object LinearLpEqualityInspection {
    fun inspect(theory: Any?, terminal: OpenTheoryResult?, advanced: Boolean, out: File) {
        val started = System.nanoTime()
        val stop = Cancellation.after(5.seconds)
        val result = buildJsonObject {
            put("scope", "retained-exact-lp-equality-metadata")
            put("sourceVerdictClaim", false)
            put("cliRoutingEquivalence", "unverified")
            put("traversalAdvanced", advanced)
            put("solveDeadlineRenewed", false)
            put("captureBudgetMs", 5000)
            put("rawTerminal", terminal?.javaClass?.simpleName)
            put("rawTerminalReason", (terminal as? OpenTheoryResult.Unknown)?.reason?.name)
            val delegate = theory?.javaClass?.declaredFields?.singleOrNull { it.name == "lpDelegate" }
                ?.apply { isAccessible = true }?.get(theory) as? Lazy<*>
            val lp = delegate?.takeIf { it.isInitialized() }?.value
            val state = lp?.let { member(it, "getState") }
            put("state", if (state == null) declined("retained-lp-state-unavailable") else captureState(state, stop))
            put("elapsedNs", System.nanoTime() - started)
        }
        out.resolve("lp-equalities.json").writeText(result.toString() + "\n")
        println(result)
    }

    fun captureModel(model: Any, stop: Cancellation): JsonObject = captured(stop) { captureExactModel(model, it) }

    private fun captureState(state: Any, stop: Cancellation): JsonObject = captured(stop) { budget ->
        val model = requireNotNull(member(state, "getModel"))
        val matrix = captureExactModel(model, budget)
        val identities = requireNotNull(member(state, "getRows"))
        val row = identities.javaClass.getMethod("row", Int::class.javaPrimitiveType)
        buildJsonObject {
            put("complete", true)
            for (name in listOf("MatrixRevision", "BoundRevision", "ObjectiveRevision", "PopRevision", "RowRevision")) {
                budget.poll()
                put(name.replaceFirstChar(Char::lowercase), member(state, "get" + name).toString())
            }
            put("model", matrix)
            put(
                "rowIdentities",
                buildJsonArray {
                    repeat(member(model, "getM") as Int) { index ->
                        budget.poll()
                        val identity = requireNotNull(row.invoke(identities, index))
                        add(
                            budget.keep(
                                buildJsonObject {
                                    put("index", index)
                                    put("id", member(identity, "getId").toString())
                                    put("depth", scalar(member(identity, "getDepth")))
                                    put("active", member(identity, "getActive") as Boolean)
                                    put("suspendedAt", scalar(member(identity, "getSuspendedAt")))
                                },
                            ),
                        )
                    }
                },
            )
            put("rowRemovalAuthorized", false)
            put("coordinateEliminationAuthorized", false)
            put("newNumericalSolves", 0)
        }
    }

    private fun captureExactModel(owner: Any, budget: CaptureBudget): JsonObject {
        budget.poll()
        val columns = member(owner, "getN") as Int
        val rows = member(owner, "getM") as Int
        val total = member(owner, "getNumVars") as Int
        if (columns !in 0..MAX_DIMENSION || rows !in 0..MAX_DIMENSION ||
            total.toLong() != columns.toLong() + rows || total > MAX_DIMENSION
        ) {
            throw CaptureStopped("dimension-limit")
        }
        val objective = requireNotNull(member(owner, "getObjective"))
        return buildJsonObject {
            put("complete", true)
            put("structuralColumns", columns)
            put("rows", rows)
            put("totalColumns", total)
            put("columns", captureColumns(owner, columns, total, budget))
            put("matrix", captureMatrix(owner, columns, budget))
            put("rowMetadata", captureRows(owner, rows, budget))
            put(
                "objective",
                budget.keep(
                    buildJsonObject {
                        put("sense", member(objective, "getSense").toString())
                        put("constant", number(member(objective, "getConstant"), budget))
                        put("scale", number(member(objective, "getScale"), budget))
                        put("externalConstant", number(member(objective, "getExternalConstant"), budget))
                    },
                ),
            )
            put("sourceVerdictClaim", false)
        }
    }

    private fun captureColumns(owner: Any, structural: Int, total: Int, budget: CaptureBudget) = buildJsonArray {
        val column = owner.javaClass.getMethod("column", Int::class.javaPrimitiveType)
        val objective = requireNotNull(member(owner, "getObjective"))
        val cost = objective.javaClass.getMethod("cost", Int::class.javaPrimitiveType)
        repeat(total) { index ->
            budget.poll()
            val value = requireNotNull(column.invoke(owner, index))
            add(
                budget.keep(
                    buildJsonObject {
                        put("index", index)
                        put("logical", index >= structural)
                        put("origin", number(member(value, "getOrigin"), budget))
                        put("integral", member(value, "getIntegral") as Boolean)
                        put("tag", member(value, "getTag") as Int)
                        put("cost", number(cost.invoke(objective, index), budget))
                        val bounds = requireNotNull(member(value, "getBounds"))
                        put("lower", side(member(bounds, "getLower"), budget))
                        put("upper", side(member(bounds, "getUpper"), budget))
                    },
                ),
            )
        }
    }

    private fun captureMatrix(owner: Any, columns: Int, budget: CaptureBudget) = buildJsonArray {
        val entries = owner.javaClass.getMethod("entries", Int::class.javaPrimitiveType)
        repeat(columns) { index ->
            budget.poll()
            add(
                budget.keep(
                    buildJsonObject {
                        put("column", index)
                        put(
                            "entries",
                            buildJsonArray {
                                for (entry in entries.invoke(owner, index) as List<*>) {
                                    budget.poll()
                                    val value = requireNotNull(entry)
                                    add(
                                        budget.keep(
                                            buildJsonObject {
                                                put("row", member(value, "getRow") as Int)
                                                put("coefficient", number(member(value, "getNumber"), budget))
                                            },
                                        ),
                                    )
                                }
                            },
                        )
                    },
                ),
            )
        }
    }

    private fun captureRows(owner: Any, rows: Int, budget: CaptureBudget) = buildJsonArray {
        val row = owner.javaClass.getMethod("row", Int::class.javaPrimitiveType)
        val rhs = owner.javaClass.getMethod("rhs", Int::class.javaPrimitiveType)
        repeat(rows) { index ->
            budget.poll()
            val value = requireNotNull(row.invoke(owner, index))
            add(
                budget.keep(
                    buildJsonObject {
                        put("index", index)
                        put("rhs", number(rhs.invoke(owner, index), budget))
                        put("global", member(value, "getGlobal") as Boolean)
                        put("strict", member(value, "getStrict") as Boolean)
                        put("premises", premises(member(value, "getPremises"), budget))
                    },
                ),
            )
        }
    }

    private fun side(value: Any?, budget: CaptureBudget): JsonElement = if (value == null) {
        JsonNull
    } else {
        budget.keep(
            buildJsonObject {
                put("value", number(member(value, "getNumber"), budget))
                put("strict", member(value, "getStrict") as Boolean)
                put("premises", premises(member(value, "getPremises"), budget))
            },
        )
    }

    private fun premises(value: Any?, budget: CaptureBudget): JsonElement = if (value == null) {
        JsonNull
    } else {
        budget.keep(
            buildJsonObject {
                put(
                    "bounds",
                    buildJsonArray {
                        for (entry in member(value, "boundEntries") as List<*>) {
                            budget.poll()
                            val bound = requireNotNull(entry)
                            add(
                                budget.keep(
                                    buildJsonObject {
                                        put("variable", member(bound, "getVariable") as Int)
                                        put("upper", member(bound, "getUpper") as Boolean)
                                        put("threshold", number(member(bound, "getThreshold"), budget))
                                    },
                                ),
                            )
                        }
                    },
                )
                put(
                    "literals",
                    buildJsonArray {
                        for (literal in member(value, "literalEntries") as List<*>) add(budget.keep(scalar(literal)))
                    },
                )
            },
        )
    }

    private fun number(value: Any?, budget: CaptureBudget): JsonElement {
        budget.poll()
        val owner = requireNotNull(value)
        val fraction = member(owner, "getValue") as BigFraction
        if (fraction.num.magnitudeBitLength() > MAX_BITS || fraction.den.magnitudeBitLength() > MAX_BITS) {
            throw CaptureStopped("rational-bit-limit")
        }
        return budget.keep(
            buildJsonObject {
                put("rational", fraction.toString())
                put("ieeeBits", scalar(member(owner, "getIeeeBits")))
            },
        )
    }

    private fun captured(stop: Cancellation, block: (CaptureBudget) -> JsonObject): JsonObject = try {
        val result = block(CaptureBudget(stop))
        if (result.toString().encodeToByteArray().size > MAX_BYTES) throw CaptureStopped("byte-limit")
        if (stop()) throw CaptureStopped("capture-deadline")
        result
    } catch (stopped: CaptureStopped) {
        declined(stopped.reason)
    }

    private fun declined(reason: String) = buildJsonObject {
        put("complete", false)
        put("decline", reason)
        put("sourceVerdictClaim", false)
    }

    private fun scalar(value: Any?): JsonElement = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    private fun member(owner: Any, name: String): Any? = owner.javaClass.getMethod(name).invoke(owner)
    private class CaptureStopped(val reason: String) : RuntimeException()

    private class CaptureBudget(private val stop: Cancellation) {
        private var entries = 0
        private var bytes = 0L

        fun poll() {
            if (stop()) throw CaptureStopped("capture-deadline")
        }

        fun <T : JsonElement> keep(value: T): T {
            poll()
            if (++entries > MAX_ENTRIES) throw CaptureStopped("entry-limit")
            bytes += value.toString().encodeToByteArray().size + 1L
            if (bytes > MAX_BYTES) throw CaptureStopped("byte-limit")
            return value
        }
    }

    private const val MAX_DIMENSION = 16384
    private const val MAX_ENTRIES = 262144
    private const val MAX_BITS = 4096
    private const val MAX_BYTES = 16 * 1024 * 1024
}
