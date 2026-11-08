package com.eignex.klause.bench.profile

import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.coefficients
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.scope
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.system
import com.eignex.klause.lp.optimization.LinearPinnedObjective
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NumericalSelectionSnapshot(private val stop: Cancellation) {
    fun journal(): Any {
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityCapture")
        return type.getDeclaredConstructor(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Cancellation::class.java).newInstance(64, 1_000_000L, 16_777_216L, stop)
    }

    fun attach(invocation: Any): Any {
        val journal = journal()
        invocation.javaClass.getDeclaredMethod("captureTo", journal.javaClass).invoke(invocation, journal)
        return journal
    }

    fun capture(journal: Any): JsonObject = buildJsonObject {
        put("maximumRecords", field(journal, "maximumRecords") as Int)
        put("maximumEntries", field(journal, "maximumEntries") as Long)
        put("maximumModeledBytes", field(journal, "maximumBytes") as Long)
        put("attempted", field(journal, "attempted") as Long)
        put("retainedRecords", records(journal).size)
        put("entries", field(journal, "entries") as Long)
        put("modeledBytes", field(journal, "bytes") as Long)
        put("constructedEntries", field(journal, "constructedEntries") as Long)
        put("constructedModeledBytes", field(journal, "constructedBytes") as Long)
        put("limited", journal.javaClass.getDeclaredMethod("getLimited").invoke(journal) as Boolean)
        put("independentProofCheck", false)
        put("fractionalAttempt", fractionalAttempt(journal))
        put("records", buildJsonArray { records(journal).forEach { if (!stop()) add(query(it)) } })
        put("inspectionStopped", stop())
    }

    @Suppress("UNCHECKED_CAST")
    fun fractionalAttempt(journal: Any): JsonObject = buildJsonObject {
        put("enabled", field(journal, "fractionalEnabled") as Boolean)
        put("attempted", field(journal, "fractionalAttempts") as Long)
        put("limited", field(journal, "fractionalAttemptLimited") as Boolean)
        put("independentProofCheck", false)
        val captured = field(journal, "fractionalAttempt")
        if (captured == null || stop()) {
            put("available", false)
            put("reason", if (stop()) "inspection-stopped" else "not-retained")
            return@buildJsonObject
        }
        val record = field(captured, "query")
        put("available", record != null && field(record, "point") != null)
        put("outcome", field(captured, "outcome") as String)
        put("observationWork", field(captured, "observationWork") as Long)
        put("observationAllocation", field(captured, "observationAllocation") as Long)
        for (name in listOf("before", "after")) put(name, buildJsonObject {
            (field(captured, name) as Map<String, Long?>).forEach { (key, value) -> put(key, value) }
        })
        if (record != null) {
            put("query", query(record))
            val numeric = field(record, "numeric") as SourceProofContext
            val generation = field(record, "generationScope") as SourceScope?
            generation?.let { put("generationOwnerMap", generationOwnerMap(numeric.atScope(it))) }
        }
        put("rejectedAttempts", field(captured, "rejectedAttempts") as Long)
        put("events", buildJsonObject {
            (field(captured, "events") as Map<String, Long>).forEach { (name, count) -> put(name, count) }
        })
        put("rejectionsLimited", field(captured, "rejectionsLimited") as Boolean)
        put("rejections", buildJsonArray {
            for (rejected in field(captured, "rejections") as List<Any>) {
                if (stop()) break
                val row = field(rejected, "inequality") as SourceInequality
                add(buildJsonObject {
                    put("checked", false)
                    put("coefficients", coefficients(row.coefficients))
                    put("rhs", row.rhs.toString())
                    put("strict", row.strict)
                    put("family", (field(rejected, "family") as Enum<*>).name)
                    put("owners", buildJsonArray {
                        (field(rejected, "owners") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
                    })
                    for (name in listOf("activity", "normSquared", "efficacy", "minimumEfficacy")) {
                        put(name, (field(rejected, name) as Double).toString())
                    }
                })
            }
        })
        put("inspectionStopped", stop())
    }

    fun generationOwnerMap(context: SourceProofContext): JsonObject = buildJsonObject {
        val premises = context.premises
        var visited = 0
        put("ordering", "source-iteration")
        put("owners", buildJsonArray {
            for ((owner, premise) in premises.keys.withIndex()) {
                if (stop()) break
                add(buildJsonObject {
                    put("owner", owner)
                    put("premise", premise)
                })
                visited++
            }
        })
        put("complete", visited == premises.size && !stop())
    }

    fun replay(journal: Any, emit: (JsonObject) -> Unit) {
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityReplay")
        val instance = type.getField("INSTANCE").get(null)
        val run = type.declaredMethods.single { it.name == "run" }
        val parents = records(journal).asSequence().filter { field(it, "point") != null && rows(it).isNotEmpty() }
            .distinctBy { record -> (field(record, "context") as SourceProofContext).scope to
                rows(record).map { field(it, "inequality") } }.take(4)
        for (record in parents) {
            if (stop()) return
            val all = rows(record).indices.toSet()
            val installed = rows(record).filter { field(it, "installed") == true }
                .mapTo(HashSet()) { field(it, "id") as Int }
            for (name in listOf("disabled", "installed", "all")) for (repeat in 0..1) {
                if (stop()) return
                val mask = when (name) {
                    "all" -> all
                    "installed" -> installed
                    else -> emptySet()
                }
                val replay = run.invoke(instance, record, mask,
                    20_000_000L, 67_108_864L, stop)
                emit(buildJsonObject {
                    put("originalQuery", field(record, "id") as Int)
                    put("mask", name)
                    put("repeat", repeat)
                    put("poolIdentity", "captured")
                    put("capturedMask", buildJsonArray { mask.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("decline", field(replay, "decline") as String?)
                    put("diagnosticOnly", true)
                    put("maximumWork", 20_000_000L)
                    put("maximumAllocation", 67_108_864L)
                    put("maximumOperations", 1)
                    put("operations", field(replay, "operations") as Int)
                    put("work", field(replay, "work") as Long)
                    put("allocation", field(replay, "allocation") as Long)
                    put("stopped", field(replay, "stopped") as Boolean)
                    field(replay, "query")?.let { put("query", query(it)) }
                    @Suppress("UNCHECKED_CAST")
                    put("queries", buildJsonArray {
                        (field(replay, "queries") as List<Any>).forEach { add(query(it)) }
                    })
                    (field(replay, "strongestBound") as SourceEvidence?)?.let {
                        put("strongestBound", evidence(field(record, "context") as SourceProofContext, it))
                    }
                })
            }
        }
    }

    fun replayActiveRows(journal: Any, emit: (JsonObject) -> Unit) {
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityReplay")
        val instance = type.getField("INSTANCE").get(null)
        val run = type.declaredMethods.single { it.name == "activeRows" }
        var inspected = 0
        for (record in records(journal)) {
            if (stop()) break
            val context = field(record, "context") as SourceProofContext
            val booleans = context.scope.condition.bools
            if (context.coordinates.none { it.kind == SourceCoordinate.Kind.Boolean && it.id !in booleans }) continue
            val replay = run.invoke(instance, record, 20_000_000L, 67_108_864L, stop, false)
            inspected++
            emit(buildJsonObject {
                put("originalQuery", field(record, "id") as Int)
                put("diagnosticOnly", true)
                put("sharedTraversalAllowance", false)
                put("maximumWork", 20_000_000L)
                put("maximumAllocation", 67_108_864L)
                put("maximumOperations", 2)
                put("operations", field(replay, "operations") as Int)
                put("work", field(replay, "work") as Long)
                put("allocation", field(replay, "allocation") as Long)
                put("stopped", field(replay, "stopped") as Boolean)
                field(replay, "query")?.let { put("query", query(it)) }
            })
        }
        emit(buildJsonObject {
            put("inspectedOpenStates", inspected)
            put("inspectionStopped", stop())
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun records(journal: Any): List<Any> = field(journal, "retained") as List<Any>

    @Suppress("UNCHECKED_CAST")
    private fun rows(record: Any): List<Any> = field(record, "cuts") as List<Any>

    @Suppress("UNCHECKED_CAST")
    private fun query(record: Any): JsonObject {
        val context = field(record, "context") as SourceProofContext
        val numeric = field(record, "numeric") as SourceProofContext
        return buildJsonObject {
            put("id", field(record, "id") as Int)
            put("state", field(record, "state") as String)
            put("boundDisposition", field(record, "boundDisposition") as String)
            (field(record, "parentQuery") as Int?)?.let { put("parentQuery", it) }
            put("scope", scope(context.scope))
            (field(record, "generationScope") as SourceScope?)?.let { put("generationScope", scope(it)) }
            put("system", system(context))
            put("numericSystem", system(numeric))
            field(record, "parent")?.let { put("parent", scope(it as SourceScope)) }
            put("incumbent", (field(record, "incumbent") as BigFraction?)?.toString())
            val point = field(record, "point") as Map<SourceCoordinate, BigFraction>?
            point?.let { put("point", coefficients(it)) }
            put("inactiveRows", buildJsonArray {
                (field(record, "inactive") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("rows", buildJsonArray {
                (field(record, "cited") as List<Pair<SourceInequality, SourceDerivation>>).forEach {
                    add(buildJsonObject {
                        put("coefficients", coefficients(it.first.coefficients))
                        put("rhs", it.first.rhs.toString())
                        put("strict", it.first.strict)
                        put("derivation", proof(it.second))
                    })
                }
            })
            put("eligibility", buildJsonObject {
                (field(record, "eligibility") as Map<String, Long>).forEach { (key, value) -> put(key, value) }
            })
            put("cuts", buildJsonArray {
                rows(record).forEach { cut -> add(buildJsonObject {
                    val row = field(cut, "inequality") as SourceInequality
                    val derivation = field(cut, "derivation") as SourceDerivation
                    put("id", field(cut, "id") as Int)
                    put("owner", field(cut, "owner") as Int)
                    put("family", (field(cut, "family") as Enum<*>?)?.name)
                    put("mixed", field(cut, "mixed") as Boolean)
                    put("owners", buildJsonArray {
                        (field(cut, "owners") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
                    })
                    put("installed", field(cut, "installed") as Boolean)
                    put("ownerActive", field(cut, "ownerActive") as Boolean)
                    put("checkedAtQuery", field(cut, "checked") as Boolean)
                    put("violation", (field(cut, "violation") as BigFraction?)?.toString())
                    put("coefficients", coefficients(row.coefficients))
                    put("rhs", row.rhs.toString())
                    put("strict", row.strict)
                    put("derivation", proof(derivation))
                    put("freshNativeCheck", SourceEvidenceVerifier(numeric).verify(SourceEvidence(numeric.scope,
                        SourceClaim.Consequence(row), derivation), stop).toString())
                }) }
            })
            for (name in listOf("bound", "refutation", "siblingBound")) {
                (field(record, name) as SourceEvidence?)?.let { put(name, evidence(context, it)) }
            }
        }
    }

    fun evidence(context: SourceProofContext, evidence: SourceEvidence): JsonObject = buildJsonObject {
        require(context.scope.revision == evidence.scope.revision &&
            context.scope.objective == evidence.scope.objective)
        put("scope", scope(evidence.scope))
        put("claim", evidence.claim::class.simpleName)
        (evidence.claim as? SourceClaim.Bound)?.let {
            put("value", (it.value as SourceValue.Rational).value.toString())
        }
        put("derivation", proof(evidence.derivation))
        val originalScope = SourceProofContext(evidence.scope, context.coordinates, context.premises,
            context.objective, context.objectiveConstant, semantics = context.semantics)
        put("freshNativeCheck", SourceEvidenceVerifier(originalScope.withLinearAuthority())
            .verify(evidence, stop).toString())
    }

    fun proof(value: SourceDerivation, depth: Int = 0): JsonObject {
        if (depth >= 64 || stop()) return buildJsonObject {
            put("kind", "Unavailable")
            put("reason", "inspection allowance")
        }
        return buildJsonObject {
            put("kind", value::class.simpleName)
            when (value) {
                is SourceDerivation.Premise -> put("id", value.id)
                is SourceDerivation.Assumption -> {
                    put("coordinateKind", value.coordinate.kind.name)
                    put("coordinate", value.coordinate.id)
                    put("upper", value.upper)
                }
                is SourceDerivation.Combination -> put("terms", JsonArray(value.terms.map { term ->
                    buildJsonObject {
                        put("weight", term.weight.toString())
                        put("derivation", proof(term.derivation, depth + 1))
                    }
                }))
                is SourceDerivation.IntegerRounding -> put("row", proof(value.inequality, depth + 1))
                is SourceDerivation.MixedIntegerRounding -> {
                    put("row", proof(value.inequality, depth + 1))
                    put("bounds", JsonArray(value.bounds.map { proof(it, depth + 1) }))
                }
                is SourceDerivation.Import -> {
                    val imported = value.evidence
                    put("scope", scope(imported.scope))
                    put("claim", imported.claim::class.simpleName)
                    when (val claim = imported.claim) {
                        is SourceClaim.Consequence -> {
                            put("coefficients", coefficients(claim.inequality.coefficients))
                            put("rhs", claim.inequality.rhs.toString())
                            put("strict", claim.inequality.strict)
                        }
                        is SourceClaim.Bound -> {
                            put("value", (claim.value as SourceValue.Rational).value.toString())
                            put("strict", claim.strict)
                        }
                        SourceClaim.Refutation -> Unit
                        else -> error("unsupported imported numerical claim: ${claim::class.simpleName}")
                    }
                    put("derivation", proof(imported.derivation, depth + 1))
                }
                is LinearPinnedObjective -> put("bound", proof(value.bound, depth + 1))
                is LinearPinnedRefutation -> put("conflict", proof(value.conflict, depth + 1))
                else -> if (value::class.simpleName == "LinearActiveRefutation") {
                    put("conflict", proof(field(value, "conflict") as SourceDerivation, depth + 1))
                } else error("unsupported captured numerical derivation: ${value::class.simpleName}")
            }
        }
    }
}
