package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.LinearAuthoritySemantics
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.div
import com.eignex.klause.util.minus
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NumericalFrontierSnapshot(
    private val context: SourceProofContext,
    private val stop: Cancellation,
    private val objectiveScale: BigFraction = BigFraction.ONE,
) {
    private val checked = IdentityHashMap<SourceEvidence, JsonObject>()
    private var remaining = 4096

    fun certificateState(invocation: Any): JsonObject {
        fun scalars(owner: Any, names: List<String>): JsonObject = buildJsonObject {
            for (name in names) {
                require(!stop())
                val value = field(owner, name) ?: continue
                when (value) {
                    is Int -> put(name, value)
                    is Long -> put(name, value)
                    is Boolean -> put(name, value)
                    else -> put(name, value.toString().also { require(it.length <= 4096) })
                }
            }
        }
        require(!stop())
        val authority = checkNotNull(field(checkNotNull(field(invocation, "component")), "authority"))
        val session = checkNotNull(field(checkNotNull(field(authority, "lp")), "session"))
        val guide = checkNotNull(field(session, "completed")) { "no retained completed guidance" }
        val metrics = checkNotNull(field(guide, "certificateMetrics")) { "no retained certificate metrics" }
        return buildJsonObject {
            put("diagnosticOnly", true)
            put("independentProofCheck", false)
            put("retainedGuidance", scalars(guide, listOf("phase", "paused", "termination", "optimalCandidate")))
            (field(session, "authority") as SourceScope?)?.let { put("ownerScope", scope(it)) }
            put("metrics", scalars(metrics, listOf("attempts", "pointChecks", "dualChecks", "rayChecks",
                "pointSuccesses", "dualSuccesses", "raySuccesses", "vectorRestarts", "verificationRestarts",
                "work", "allocation", "maxBits", "decline", "phase", "basisWork")))
            field(metrics, "dualRejection")?.let {
                put("dualRejection", scalars(it, listOf("column", "upper", "reduced")))
            }
            field(metrics, "vectorRejection")?.let {
                put("vectorRejection", scalars(it, listOf("commonDenominator", "limit")))
            }
            field(metrics, "entryRejection")?.let {
                put("entryRejection", scalars(it, listOf("row", "value")))
            }
            field(metrics, "basisRepair")?.let {
                put("basisRepair", scalars(it, listOf("peeledRows", "peeledColumns", "coreRows", "components",
                    "largestComponent", "solvedComponents", "factorWork", "factorAllocation", "openBasicColumns",
                    "decline", "restrictedComponents", "restrictedColumns", "largestRestrictedColumns")))
            }
        }
    }

    fun cuts(invocation: Any): JsonObject {
        val authority = checkNotNull(field(checkNotNull(field(invocation, "component")), "authority"))
        val template = field(authority, "template")
        val retained = template?.let { field(it, "cuts") }
        val query = field(authority, "query")
        val queryContext = query?.let { field(it, "context") as SourceProofContext }
        val numeric = template?.let { field(it, "numeric") as SourceProofContext }
        val verifierContext = if (numeric == null || queryContext == null) null else SourceProofContext(
            queryContext.scope, numeric.coordinates, numeric.premises, numeric.objective, numeric.objectiveConstant)
        @Suppress("UNCHECKED_CAST")
        val rows = retained?.let { field(it, "rows") as List<Any> }.orEmpty()
        @Suppress("UNCHECKED_CAST")
        val inactive = query?.let { field(it, "inactive") as Set<Int> }.orEmpty()
        @Suppress("UNCHECKED_CAST")
        val originals = template?.let { field(it, "rows") as List<Any> }.orEmpty()
        fun proof(value: SourceDerivation, depth: Int = 0): JsonObject {
            require(depth < 32 && !stop())
            return buildJsonObject {
                put("kind", value::class.simpleName)
                when (value) {
                    is SourceDerivation.Premise -> put("id", value.id)
                    is SourceDerivation.Combination -> put("terms", buildJsonArray {
                        value.terms.forEach { term -> add(buildJsonObject {
                            put("weight", term.weight.toString())
                            put("derivation", proof(term.derivation, depth + 1))
                        }) }
                    })
                    is SourceDerivation.IntegerRounding -> put("row", proof(value.inequality, depth + 1))
                    is SourceDerivation.MixedIntegerRounding -> {
                        put("row", proof(value.inequality, depth + 1))
                        put("bounds", JsonArray(value.bounds.map { proof(it, depth + 1) }))
                    }
                    else -> error("unsupported retained source cut derivation")
                }
            }
        }
        return buildJsonObject {
            put("independentProofCheck", false)
            queryContext?.let { put("scope", scope(it.scope)) }
            put("rows", buildJsonArray {
                rows.forEachIndexed { index, cut ->
                    require(!stop())
                    val row = field(cut, "inequality") as SourceInequality
                    val derivation = field(cut, "derivation") as SourceDerivation
                    add(buildJsonObject {
                        put("owner", field(cut, "owner") as Int)
                        put("mixed", field(cut, "mixed") as Boolean)
                        put("active", query != null && originals.size + index !in inactive)
                        put("coefficients", coefficients(row.coefficients))
                        put("rhs", row.rhs.toString())
                        put("derivation", proof(derivation))
                        put("sourceCheck", verifierContext?.let {
                            SourceEvidenceVerifier(it).verify(SourceEvidence(it.scope,
                                SourceClaim.Consequence(row), derivation), stop).toString()
                        })
                    })
                }
            })
        }
    }

    fun probeRoot(invocation: Any, emit: (JsonObject) -> Unit) {
        val root = checkNotNull(field(checkNotNull(field(invocation, "component")), "root"))
        val result = checkNotNull(field(root, "result"))
        @Suppress("UNCHECKED_CAST")
        val candidates = field(result, "fractional") as List<Pair<SourceCoordinate, BigFraction>>
        require(candidates.size <= 128)
        for ((coordinate, value) in candidates) {
            val integer = value.num / value.den
            val floor = BigFraction.of(if (value.signum() < 0) integer - BIG_ONE else integer, BIG_ONE)
            for (upper in listOf(true, false)) {
                if (stop()) return
                val threshold = if (upper) floor else floor + BigFraction.ONE
                val condition = context.scope.condition
                val childCondition = SourceCondition(condition.bools,
                    condition.lower + if (upper) emptyMap() else mapOf(coordinate to threshold),
                    condition.upper + if (upper) mapOf(coordinate to threshold) else emptyMap())
                val child = SourceProofContext(context.scope.copy(condition = childCondition), context.coordinates,
                    context.premises, context.objective, context.objectiveConstant,
                    LinearAuthoritySemantics(context), context.semantics)
                val probe = open(child,
                    TheoryParams(maxLeaves = 1, timeout = stop, cancellation = stop, randomSeed = 3L))
                try {
                    run(probe) { _, _ -> }
                    val node = checkNotNull(field(checkNotNull(field(probe, "component")), "root"))
                    val checked = field(node, "result") ?: continue
                    val bound = field(checked, "bound") as SourceEvidence?
                    val evidence = field(checked, "evidence") as SourceEvidence?
                    emit(buildJsonObject {
                        put("coordinate", coordinate.id)
                        put("value", value.toString())
                        put("upper", upper)
                        put("threshold", threshold.toString())
                        put("diagnosticOnly", true)
                        put("sharedTraversalAllowance", false)
                        put("operations", stats(probe).smt.sourceLp.operations)
                        put("floatWork", stats(probe).smt.sourceLp.floatWork)
                        bound?.let { put("bound", bound(it)) }
                        evidence?.let {
                            put("claim", it.claim::class.simpleName)
                            put("check", SourceEvidenceVerifier(child.withLinearAuthority())
                                .verify(it, stop).toString())
                        }
                    })
                } finally {
                    (probe as AutoCloseable).close()
                }
            }
        }
    }

    fun capture(invocation: Any): JsonObject {
        val stages = stats(invocation).smt.completeSearchStages.orEmpty()
        val pending = ArrayList<JsonObject>()
        val covered = ArrayList<JsonObject>()
        val branches = ArrayList<JsonObject>()
        var nodes = 0L
        fun walk(node: Any, inherited: SourceEvidence?, parent: SourceEvidence? = null) {
            require(remaining-- > 0 && !stop()) { "frontier capture allowance exhausted" }
            nodes++
            val nodeCondition = field(node, "condition") as SourceCondition
            val result = field(node, "result")
            val partial = field(node, "partialBound", optional = true) as SourceEvidence?
            val own = result?.let { field(it, "bound") as SourceEvidence? } ?: partial
            val best = listOfNotNull(inherited, own).reduceOrNull { left, right ->
                val before = ((left.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                val after = ((right.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                if (if (context.scope.objective?.sense == ObjectiveSense.MINIMIZE) after > before
                    else after < before) right else left
            }
            val closure = field(node, "coveredBound", optional = true) as SourceEvidence?
            val split = field(node, "split")
            if (result != null) branches += buildJsonObject {
                put("condition", condition(nodeCondition))
                own?.let { put("bound", bound(it)) }
                parent?.let { put("parentBound", bound(it)) }
                if (own != null && parent != null) {
                    val after = ((own.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                    val before = ((parent.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                    val gain = if (context.scope.objective?.sense == ObjectiveSense.MINIMIZE) after - before
                        else before - after
                    put("checkedBoundGain", gain.toString())
                }
                @Suppress("UNCHECKED_CAST")
                val candidates = field(result, "fractional", optional = true)
                    as? List<Pair<SourceCoordinate, BigFraction>>
                put("fractional", coefficients(candidates.orEmpty().toMap()))
                if (split != null && field(split, "coordinate", optional = true) != null) {
                    put("coordinate", (field(split, "coordinate") as SourceCoordinate).id)
                    put("threshold", field(split, "threshold").toString())
                }
                put("terminal", (field(result, "evidence") as SourceEvidence?)?.claim?.let { it::class.simpleName })
                put("boundClosed", closure != null)
            }
            when {
                closure != null -> covered += buildJsonObject {
                    put("condition", condition(nodeCondition))
                    put("bound", bound(closure))
                }
                split != null -> {
                    walk(checkNotNull(field(split, "below")), best, own)
                    walk(checkNotNull(field(split, "above")), best, own)
                }
                result?.let { field(it, "evidence") } == null -> pending += buildJsonObject {
                    put("condition", condition(nodeCondition))
                    put("state", if (partial != null && result == null) "pending-separation"
                        else if (result == null) "unvisited" else "unresolved")
                    best?.let {
                        put("bound", bound(it))
                        put("inherited", it.scope.condition != nodeCondition)
                    }
                }
            }
        }
        walk(checkNotNull(field(checkNotNull(field(invocation, "component")), "root")), null)
        check(nodes == stages.getValue("numericalRetainedNodes"))
        check(pending.size.toLong() == stages.getValue("numericalPendingLeaves"))
        check(covered.size.toLong() == (stages["numericalIncumbentBoundClosures"] ?: 0L) +
            (stages["numericalInheritedBoundClosures"] ?: 0L))
        return buildJsonObject {
            put("retainedNodes", nodes)
            put("pending", JsonArray(pending))
            put("boundClosures", JsonArray(covered))
            put("checkedBounds", checked.size)
            put("checkedNodes", JsonArray(branches))
        }
    }

    fun bound(evidence: SourceEvidence): JsonObject = checked.getOrPut(evidence) {
        val capturedScope = evidence.scope
        val root = context.scope
        check(
            capturedScope.revision == root.revision && capturedScope.objective == root.objective &&
                root.condition.bools.all { (id, value) -> capturedScope.condition.bools[id] == value } &&
                root.condition.lower.all { (id, value) ->
                    capturedScope.condition.lower[id]?.let { it >= value } == true
                } &&
                root.condition.upper.all { (id, value) ->
                    capturedScope.condition.upper[id]?.let { it <= value } == true
                },
        ) { "captured bound has a foreign source scope" }
        val scoped = SourceProofContext(evidence.scope, context.coordinates, context.premises,
            context.objective, context.objectiveConstant, LinearAuthoritySemantics(context), context.semantics)
        check(SourceEvidenceVerifier(scoped).verify(evidence, stop) == SourceEvidenceCheck.Checked) {
            "captured bound does not recheck in its original scope"
        }
        val claim = evidence.claim as SourceClaim.Bound
        val value = (claim.value as SourceValue.Rational).value
        buildJsonObject {
            put("nativeValue", value.toString())
            put("sourceUnitValue", (value * objectiveScale.reciprocal()).toString())
            put("strict", claim.strict)
            put("checked", true)
            put("scope", scope(evidence.scope))
        }
    }

    companion object {
        // Reflection keeps solver ownership and inspection types out of the published API.
        fun open(
            context: SourceProofContext, params: TheoryParams, history: Boolean = false, frontier: Boolean = false,
            activeRows: Boolean = false, supportBranching: Boolean = false, latestFeedback: Boolean = true,
            fullFeedback: Boolean = false, aggregateBranching: Boolean = false,
        ): Any {
            val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
            val state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
            val type = Class.forName("com.eignex.klause.solver.pipeline.LinearAuthorityInvocation")
            val authorityType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthority")
            val fundingType = Class.forName("com.eignex.klause.lp.SourceLpFunding")
            val funding = fundingType.getDeclaredConstructor(
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            ).newInstance(params.sourceLpWorkLimit, params.sourceLpAllocationLimit)
            val requestType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest")
            val request = if (fullFeedback) requestType.getField("Companion").get(null).let { companion ->
                companion.javaClass.getMethod("getFull").invoke(companion)
            } else null
            return type.getDeclaredConstructor(SourceProofContext::class.java, TheoryParams::class.java,
                Function0::class.java, stateType, Boolean::class.javaPrimitiveType, authorityType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, fundingType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Function1::class.java, Boolean::class.javaPrimitiveType, requestType, Boolean::class.javaPrimitiveType,
                Function0::class.java, Function0::class.java, Boolean::class.javaPrimitiveType, Function3::class.java)
                .newInstance(context, params, { null }, state, history, null, frontier, false,
                    funding, frontier && params.sourceCuts, activeRows, supportBranching, null, latestFeedback,
                    request, aggregateBranching, null, null, false, null)
        }

        fun run(
            invocation: Any, onProgress: (Any) -> Unit = {}, onIncumbent: (Sample, BigFraction) -> Unit,
        ): Any {
            val advance = invocation.javaClass.methods.single { it.name == "runSlice" && it.parameterCount == 4 }
            while (true) {
                val result = advance.invoke(invocation, Cancellation.Never, Long.MAX_VALUE, 64L, onIncumbent)
                onProgress(invocation)
                if (result != null) return result
            }
        }

        fun stats(invocation: Any): SolveStats = invocation.javaClass.methods
            .single { it.name == "getStats" && it.parameterCount == 0 }.invoke(invocation) as SolveStats

        fun field(owner: Any, name: String, optional: Boolean = false): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val selected = type.declaredFields.singleOrNull { it.name == name }
                if (selected != null) return selected.apply { isAccessible = true }.get(owner)
                type = type.superclass
            }
            require(optional) { "missing $name on ${owner.javaClass.name}" }
            return null
        }

        fun current(invocation: Any): Any {
            val path = field(checkNotNull(field(invocation, "component")), "path") as List<*>
            return checkNotNull((path.last() as Pair<*, *>).second)
        }

        fun scope(value: SourceScope): JsonObject = buildJsonObject {
            put("revision", value.revision.identity)
            put("semantics", value.revision.semantics)
            value.objective?.let {
                put("objective", it.identity)
                put("sense", it.sense.name)
            }
            put("condition", condition(value.condition))
        }

        fun condition(value: SourceCondition): JsonObject = buildJsonObject {
            put("bools", buildJsonObject {
                value.bools.toSortedMap().forEach { (id, pin) -> put(id.toString(), pin) }
            })
            put("lower", coefficients(value.lower))
            put("upper", coefficients(value.upper))
        }

        fun coefficients(values: Map<SourceCoordinate, BigFraction>): JsonArray = buildJsonArray {
            values.entries.sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.id })).forEach { (id, value) ->
                add(buildJsonObject {
                    put("kind", id.kind.name)
                    put("id", id.id)
                    put("value", value.toString())
                })
            }
        }

        fun system(context: SourceProofContext): JsonObject = buildJsonObject {
            put("objective", coefficients(context.objective))
            put("constant", context.objectiveConstant.toString())
            put("sense", context.scope.objective?.sense?.name)
            put("condition", condition(context.scope.condition))
            put("premises", buildJsonArray {
                context.premises.toSortedMap().forEach { (id, premise) -> add(buildJsonObject {
                    put("id", id)
                    put("coefficients", coefficients(premise.inequality.coefficients))
                    put("rhs", premise.inequality.rhs.toString())
                    put("strict", premise.inequality.strict)
                    put("guards", buildJsonObject {
                        premise.guards.toSortedMap().forEach { (key, value) -> put(key.toString(), value) }
                    })
                    put("integerPins", coefficients(premise.integerPins))
                }) }
            })
        }
    }
}
