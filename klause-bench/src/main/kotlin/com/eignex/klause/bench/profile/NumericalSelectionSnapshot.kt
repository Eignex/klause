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
        val feedback = field(checkNotNull(field(invocation, "component")), "feedback")
        val journal = feedback?.let { checkNotNull(field(it, "journal")) } ?: journal()
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
        put("records", buildJsonArray { records(journal).forEach { if (!stop()) add(query(it)) } })
        put("inspectionStopped", stop())
    }

    fun replay(journal: Any, emit: (JsonObject) -> Unit) {
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityReplay")
        val instance = type.getField("INSTANCE").get(null)
        val run = type.declaredMethods.single { it.name == "run" }
        val select = type.declaredMethods.single { it.name == "runSelected" }
        val parents = records(journal).asSequence().filter { field(it, "point") != null }
            .distinctBy { (field(it, "context") as SourceProofContext).scope }.take(4)
        for (record in parents) {
            if (stop()) return
            val all = rows(record).indices.toSet()
            for (name in listOf("disabled", "all", "selected")) for (repeat in 0..1) {
                if (stop()) return
                val replay = if (name == "selected") {
                    select.invoke(instance, record, 20_000_000L, 67_108_864L, stop)
                } else run.invoke(instance, record, if (name == "all") all else emptySet<Int>(),
                    20_000_000L, 67_108_864L, stop)
                emit(buildJsonObject {
                    put("originalQuery", field(record, "id") as Int)
                    put("mask", name)
                    put("repeat", repeat)
                    put("diagnosticOnly", true)
                    put("maximumWork", 20_000_000L)
                    put("maximumAllocation", 67_108_864L)
                    put("maximumOperations", if (name == "selected") 2 else 1)
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

    private fun proof(value: SourceDerivation, depth: Int = 0): JsonObject {
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
                is LinearPinnedObjective -> put("bound", proof(value.bound, depth + 1))
                is LinearPinnedRefutation -> put("conflict", proof(value.conflict, depth + 1))
                else -> if (value::class.simpleName == "LinearActiveRefutation") {
                    put("conflict", proof(field(value, "conflict") as SourceDerivation, depth + 1))
                } else error("unsupported captured numerical derivation")
            }
        }
    }
}
