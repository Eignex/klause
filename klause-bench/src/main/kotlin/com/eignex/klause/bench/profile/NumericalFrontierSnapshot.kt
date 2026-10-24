package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.LinearAuthoritySemantics
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
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

    fun capture(invocation: Any): JsonObject {
        val stages = stats(invocation).smt.completeSearchStages.orEmpty()
        val pending = ArrayList<JsonObject>()
        val covered = ArrayList<JsonObject>()
        var nodes = 0L
        fun walk(node: Any, inherited: SourceEvidence?) {
            require(remaining-- > 0 && !stop()) { "frontier capture allowance exhausted" }
            nodes++
            val nodeCondition = field(node, "condition") as SourceCondition
            val result = field(node, "result")
            val own = result?.let { field(it, "bound") as SourceEvidence? }
            val best = listOfNotNull(inherited, own).reduceOrNull { left, right ->
                val before = ((left.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                val after = ((right.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                if (if (context.scope.objective?.sense == ObjectiveSense.MINIMIZE) after > before
                    else after < before) right else left
            }
            val closure = field(node, "coveredBound", optional = true) as SourceEvidence?
            val split = field(node, "split")
            when {
                closure != null -> covered += buildJsonObject {
                    put("condition", condition(nodeCondition))
                    put("bound", bound(closure))
                }
                split != null -> {
                    walk(checkNotNull(field(split, "below")), best)
                    walk(checkNotNull(field(split, "above")), best)
                }
                result?.let { field(it, "evidence") } == null -> pending += buildJsonObject {
                    put("condition", condition(nodeCondition))
                    put("state", if (result == null) "unvisited" else "unresolved")
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
        check(covered.size.toLong() == (stages["numericalIncumbentBoundClosures"] ?: 0L))
        return buildJsonObject {
            put("retainedNodes", nodes)
            put("pending", JsonArray(pending))
            put("boundClosures", JsonArray(covered))
            put("checkedBounds", checked.size)
        }
    }

    fun bound(evidence: SourceEvidence): JsonObject = checked.getOrPut(evidence) {
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
        fun open(context: SourceProofContext, params: TheoryParams): Any {
            val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
            val state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
            val type = Class.forName("com.eignex.klause.solver.pipeline.LinearAuthorityInvocation")
            return type.getDeclaredConstructor(SourceProofContext::class.java, TheoryParams::class.java,
                Function0::class.java, stateType).newInstance(context, params, { null }, state)
        }

        fun run(invocation: Any, onIncumbent: (Sample, BigFraction) -> Unit): Any = checkNotNull(
            invocation.javaClass.methods.single { it.name == "runSlice" && it.parameterCount == 4 }
                .invoke(invocation, Cancellation.Never, Long.MAX_VALUE, -1L, onIncumbent),
        )

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
