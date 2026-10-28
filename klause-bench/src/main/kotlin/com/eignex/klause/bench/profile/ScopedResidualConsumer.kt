package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

/** Separate retained-node cutoff and prepared-root separator comparisons using qualified snapshots. */
object ScopedResidualConsumer {
    /** Forward trace arguments; compare one control and one catalog context with checked incumbents. */
    @JvmStatic
    fun main(args: Array<String>) {
        var consumedCatalog = false
        var consumedControl = false
        ConditionalComponentTrace.run(args) { context ->
            val catalog = context.snapshot.sourceIdentity["kind"]?.jsonPrimitive?.content == "catalog"
            when {
                context.snapshot.activeCutoff == null -> decline("no-live-incumbent")
                catalog && consumedCatalog -> decline("one-retained-source-context-limit")
                !catalog && consumedControl -> decline("one-retained-control-context-limit")
                else -> {
                    val retained = compare(context, 2_000L, 3L, fromRoot = false)
                    val root = compare(context, 2_000L, 3L, fromRoot = true)
                    val observed = retained["naturalDecomposition"] != null || root["ordinary"] != null ||
                        (root["admission"] as? JsonObject)?.get("cutoffClosed") == JsonPrimitive(true)
                    if (observed) {
                        if (catalog) consumedCatalog = true else consumedControl = true
                    }
                    buildJsonObject {
                        put("retainedNode", retained)
                        put("preparedRoot", root)
                    }
                }
            }
        }
    }

    private data class Admission(val residual: ComponentResidual?, val reason: String?, val budgetRows: Int)
    private data class OrdinaryControl(
        val lp: LpEmphasis? = null,
        val pbCutoff: Boolean = false,
        val objectiveOrder: Boolean = false,
        val biased: Int? = null,
    )

    private class Study(
        val context: ResidualProbeContext,
        val millis: Long,
        val seed: Long,
        val fromRoot: Boolean,
    ) {
        val receipts = ArrayList<JsonObject>()
    }

    private class Invocation(
        val context: ResidualProbeContext,
        val allowance: ComponentAllowance,
        val identity: String,
        val check: (List<Boolean>, ComponentAllowance) -> Boolean,
    ) {
        val snapshot get() = context.snapshot
    }

    private data class Opening(
        val context: ResidualProbeContext?,
        val reason: String? = null,
        val closed: Boolean = false,
        val file: String? = null,
    )

    private fun admit(invocation: Invocation): Admission {
        val snapshot = invocation.snapshot
        val allowance = invocation.allowance
        val cutoff = snapshot.activeCutoff?.strictUpper?.let(::BigInteger)
            ?: return Admission(null, "no-live-incumbent", 0)
        val codec = ProblemJson()
        val prepared = codec.decodeFromJsonElement(snapshot.preparedProblem)
        val fixed = snapshot.values.mapIndexedNotNull { v, value -> value?.let { v to it } }.toMap()
        val clauses = prepared.factors.map { (it as Clause).literals.toList() }.toMutableList()
        var budgetRows = 0
        for (entry in snapshot.learned) {
            if (!allowance.visit()) return Admission(null, "containing-allowance", budgetRows)
            val factor = codec.decodeFromJsonElement(entry.problem).factors.single()
            when (factor) {
                is Clause -> clauses += factor.literals.toList()
                is PseudoBoolean -> {
                    if (!ResidualBudgetGate.impliedByCutoff(
                            factor,
                            snapshot.boolWeights,
                            BigInteger.valueOf(snapshot.objectiveConstant),
                            cutoff,
                            fixed,
                            allowance,
                        )
                    ) return Admission(null, "non-budget-pb-remains-enforcing", budgetRows)
                    budgetRows++
                }
                else -> return Admission(null, "unsupported-enforcing-reader", budgetRows)
            }
        }
        val residual = ComponentResidual(
            invocation.identity,
            clauses.toList(),
            snapshot.boolWeights,
            BigInteger.valueOf(snapshot.objectiveConstant),
            fixed,
            cutoff,
        )
        return Admission(residual, null, budgetRows)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun compare(context: ResidualProbeContext, millis: Long, seed: Long, fromRoot: Boolean): JsonObject {
        val cutoff = context.snapshot.activeCutoff?.strictUpper?.let(::BigInteger)
            ?: return decline("no-live-incumbent")
        val source = context.originalProblem
        if (source.numIntVars != 0 || source.numRealVars != 0 ||
            source.factors.any { f -> f !is Clause || f.literals.any {
                Lit.variable(it) !in 0 until source.numBoolVars
            } } ||
            context.originalObjective.boolWeights.size > source.numBoolVars ||
            context.originalObjective.intCoefficients.any { it != 0L } ||
            context.originalObjective.realCoefficients.any { it != 0.0 }
        ) return decline("original-source-check-unavailable")
        val study = Study(context, millis, seed, fromRoot)
        val admission = funded(study) { invocation ->
            val admitted = admit(invocation)
            val residual = admitted.residual ?: return@funded decline(checkNotNull(admitted.reason))
            val partition = residual.capture(invocation.allowance) ?: return@funded decline("structural-allowance")
            val floor = ScopedComponentBounds(partition.offset, partition.scopes, cutoff)
                .floor(partition.scopes.map { ComponentCostBounds.opening(it) })
            buildJsonObject {
                put("components", partition.scopes.size)
                put("componentVariables", partition.scopes.map { it.variables.size }.joinToString(","))
                put("budgetOrDomainImpliedLearnedRows", admitted.budgetRows)
                put("trivialFloor", floor.toString())
                put("ordinaryAdditiveBoundCloses", floor >= cutoff)
                if (!fromRoot && partition.scopes.size < 2) put("decline", "no-independent-enforcing-children")
                if (floor >= cutoff) put("decline", "ordinary-additive-bound-already-closes")
            }
        }
        if (admission["decline"] != null) return buildJsonObject {
            put("opening", if (fromRoot) "prepared-root" else "retained-node")
            put("admission", admission)
            put("sourceVerdictClaim", false)
            put("witnessChecks", JsonArray(study.receipts))
        }
        val natural = if (admission["components"] == JsonPrimitive(1)) {
            decline("one-component-falls-back-to-ordinary")
        } else funded(study) { invocation ->
            val admitted = admit(invocation)
            val residual = admitted.residual ?: return@funded decline(checkNotNull(admitted.reason))
            ScopedComponentProfile.composition(
                residual,
                cutoff,
                millis,
                seed,
                invocation.allowance,
                sourceCheck = invocation.check,
            )
        }
        val separator = funded(study) { invocation ->
            val admitted = admit(invocation)
            val residual = admitted.residual ?: return@funded decline(checkNotNull(admitted.reason))
            ScopedComponentProfile.separator(residual, cutoff, millis, seed, invocation.check, invocation.allowance)
        }
        val variable = separator["variable"]?.jsonPrimitive?.content?.toInt()
        return buildJsonObject {
            put("opening", if (fromRoot) "prepared-root-after-cutoff-propagation" else "retained-node")
            put("parentConditionIdentity", context.snapshot.conditionIdentity)
            put("comparisonSeed", seed)
            put("comparisonMillisPerArm", millis)
            put("cutoff", cutoff.toString())
            put("sourceVerdictClaim", false)
            put("witnessCheck", "native-original-Problem-clauses-and-exact-objective")
            put("childResultReuse", false)
            put("admission", admission)
            put("ordinary", ordinary(study, OrdinaryControl()))
            put("ordinaryLp", ordinary(study, OrdinaryControl(lp = LpEmphasis.DEFAULT)))
            put("ordinaryStrong", ordinary(study,
                OrdinaryControl(lp = LpEmphasis.AGGRESSIVE, pbCutoff = true, objectiveOrder = true)))
            put("naturalDecomposition", natural)
            put("separatorDecomposition", separator)
            if (variable != null) put("ordinarySeparatorOrder", ordinary(study, OrdinaryControl(biased = variable)))
            put("witnessChecks", JsonArray(study.receipts))
        }
    }

    private fun originalChecker(
        context: ResidualProbeContext,
        receipts: MutableList<JsonObject>,
        weights: List<Long> = context.snapshot.boolWeights,
        constant: Long = context.snapshot.objectiveConstant,
    ): (List<Boolean>, ComponentAllowance) -> Boolean = { values, allowance ->
        val source = context.originalProblem
        val objective = context.originalObjective
        val cells = source.factors.sumOf { (it as Clause).literals.size.toLong() } + source.numBoolVars * 4L
        val bytes = (source.numBoolVars + values.size) * 64L
        if (!allowance.visit(cells) || !allowance.retain(bytes)) {
            false
        } else {
            val original = context.reconstruct(Sample(values.toBooleanArray(), LongArray(0)))
            val sourceValues = List(source.numBoolVars) { original.boolValue(it) }
            val sourceWeights = objective.boolWeights
            val sourceCost = ResidualSnapshots.objective(
                List(source.numBoolVars) { sourceWeights.getOrElse(it) { 0L } }, objective.constant, sourceValues,
            )
            val preparedCost = ResidualSnapshots.objective(weights, constant, values)
            val checked = sourceCost == preparedCost && source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, sourceValues[Lit.variable(it)]) }
            }
            receipts += buildJsonObject {
                put("preparedObjective", preparedCost.toString())
                put("sourceObjective", sourceCost.toString())
                put("preparedWitness", JsonArray(values.map(::JsonPrimitive)))
                put("sourceWitness", JsonArray(sourceValues.map(::JsonPrimitive)))
                put("nativeOriginalProblemChecked", checked)
            }
            checked
        }
    }

    private fun ordinary(study: Study, control: OrdinaryControl): JsonObject = funded(study) { invocation ->
        val snapshot = invocation.snapshot
        val allowance = invocation.allowance
        val mapping = snapshot.residualToPrepared
        val model = ResidualSnapshots.enforcingProblem(snapshot)
        val cells = model.factors.sumOf { it.boolVars.size.toLong() + 2L } + mapping.size * 8L
        if (!allowance.retain(cells * 16L) || !allowance.visit(cells)) return@funded decline("containing-allowance")
        val remapping = VarRemap(mapping.toIntArray(), IntArray(0))
        val factors = ArrayList<Factor>(model.factors.size)
        for (factor in model.factors) {
            if (!allowance.visit()) return@funded decline("containing-allowance")
            factors += factor.remap(remapping)
        }
        val decisions = ArrayList<JsonObject>()
        val run = ResidualReplayControls.solve(
            mapping.toIntArray(), factors, snapshot.boolWeights.toLongArray(), allowance.stop, study.seed,
        ) { base: BacktrackParams ->
            base.copy(
                nodeBudget = allowance.nodes,
                pbObjectiveCutoff = control.pbCutoff,
                objectiveGuidedValues = control.objectiveOrder,
                lpConfig = control.lp?.let { LpConfig(it) },
                variableSelector = ScopedComponentProfile.DecisionTrace(
                    base.variableSelector, control.biased?.let { mapping.indexOf(it) }?.takeIf { it >= 0 }, decisions,
                ),
            )
        }
        val witness = run.result?.assignment?.let { sample ->
            snapshot.values.map { it == true }.toMutableList().apply {
                mapping.forEachIndexed { i, v -> this[v] = sample.boolValue(i) }
            }.toList()
        }
        val cost = witness?.let { ResidualSnapshots.objective(snapshot.boolWeights, snapshot.objectiveConstant, it) }
        check(cost == null || cost < BigInteger(checkNotNull(snapshot.activeCutoff).strictUpper))
        val checked = witness != null && invocation.check(witness, allowance)
        check(witness == null || checked || allowance.stop())
        buildJsonObject {
            put("authority", "captured-conditions-and-cutoff")
            put("result", run.result?.let { it::class.simpleName } ?: "NotRun")
            put("cutoffClosed", run.result is MinimizeResult.Infeasible && !allowance.stop())
            put("objective", cost?.toString())
            put("originalSourceWitnessChecked", checked)
            put("lp", control.lp?.name)
            put("pbObjectiveCutoff", control.pbCutoff)
            put("objectiveGuidedValues", control.objectiveOrder)
            put("decisionTrace", JsonArray(decisions))
            put("decisionTraceCoordinates", "residualToPrepared")
            put("buildNs", run.buildNanos)
            put("solveNs", run.solveNanos)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun preparedRoot(study: Study, allowance: ComponentAllowance): Opening {
        val parent = study.context
        val root = parent.preparedRoot
        val objective = parent.preparedObjective
        val coefficients = objective.boolWeights
        val cutoff = checkNotNull(parent.snapshot.activeCutoff)
        if (root.numIntVars != 0 || root.numRealVars != 0 || root.factors.any { it !is Clause } ||
            coefficients.size > root.numBoolVars || cutoff.witness.size != root.numBoolVars ||
            objective.intCoefficients.any { it != 0L } || objective.realCoefficients.any { it != 0.0 }
        ) return Opening(null, "unsupported-prepared-root")
        val cells = root.factors.sumOf { (it as Clause).literals.size.toLong() } + root.numBoolVars * 8L
        if (!allowance.visit(cells) || !allowance.retain(cells * 32L)) return Opening(null, "root-allowance")
        val implied = root.baked as? PropagationResult.Implied ?: return Opening(null, "unavailable-root-fixpoint")
        if (implied.boolKeys.any { it !in 0 until root.numBoolVars }) {
            return Opening(null, "unnameable-prepared-root-fact")
        }
        val weights = List(root.numBoolVars) { coefficients.getOrElse(it) { 0L } }
        val point = cutoff.witness.toMutableList()
        implied.forEachBool { v, value -> point[v] = value }
        val rootCutoff = cutoff.copy(witness = point.toList())
        val checker = originalChecker(parent, study.receipts, weights, objective.constant)
        if (ResidualSnapshots.objective(weights, objective.constant, point) != BigInteger(cutoff.strictUpper) ||
            !checker(point, allowance)
        ) return Opening(null, "prepared-root-incumbent-unavailable")
        val factors = root.factors.toMutableList()
        implied.forEachBool { v, value -> factors += Clause(intArrayOf(Lit.make(v, value))) }
        val source = Problem(root.numBoolVars, 0, emptyArray(), factors.toTypedArray(),
            settings = root.settings, semantics = root.semantics)
        val rhs = BigInteger(cutoff.strictUpper) - BigInteger.ONE - BigInteger.valueOf(objective.constant)
        val budget = PseudoBoolean(
            weights.map { parseBigInt(it.toString()) }.toTypedArray(),
            IntArray(weights.size) { Lit.make(it, true) }, PbOp.LE,
            parseBigInt(rhs.toString()),
        )
        val constrained = Problem(root.numBoolVars, 0, emptyArray(), (factors + budget).toTypedArray(),
            settings = root.settings, semantics = root.semantics)
            .bake(allowance.stop)
        if (allowance.stop()) return Opening(null, "root-propagation-allowance")
        val propagated = constrained.baked
        val closed = propagated is PropagationResult.Unsat
        val values = if (propagated is PropagationResult.Implied) {
            List(root.numBoolVars) { propagated.boolValueOrNull(it) }
        } else if (closed) {
            List(root.numBoolVars) { implied.boolValueOrNull(it) }
        } else return Opening(null, "incomplete-root-propagation")
        val metadata = JsonObject(parent.snapshot.preparationIdentity + buildJsonObject {
            put("opening", "prepared-root-after-cutoff-propagation")
            put("incumbentParentCondition", parent.snapshot.conditionIdentity)
        })
        val capture = ResidualSnapshots.capture(
            source, objective, values, LearnedResidualInspection(emptyList(), 0, null),
            parent.snapshot.sourceIdentity, metadata, rootCutoff, allowance.stop, ResidualSnapshots.MAX_BYTES,
        )
        val bytes = capture.bytes ?: return Opening(null, "root-capture:${capture.decline}")
        if (!allowance.visit(bytes.size.toLong()) || !allowance.retain(bytes.size * 4L)) {
            return Opening(null, "root-capture-allowance")
        }
        val snapshot = ResidualSnapshots.load(bytes.decodeToString())
        val file = "root-${snapshot.conditionIdentity}.residual.json"
        File(System.getenv("OUT") ?: "build/profile-out").resolve(file).writeBytes(bytes)
        if (closed) return Opening(null, "ordinary-root-propagation-closes-cutoff", true, file)
        return Opening(
            ResidualProbeContext(snapshot, parent.reconstruct, parent.originalProblem, parent.originalObjective,
                root, objective), file = file,
        )
    }

    private fun funded(study: Study, run: (Invocation) -> JsonObject): JsonObject {
        val start = System.nanoTime()
        val allowance = ComponentAllowance(Cancellation.after(study.millis.milliseconds))
        if (!allowance.visit()) return decline("containing-allowance")
        val opening = if (study.fromRoot) preparedRoot(study, allowance) else Opening(study.context)
        val context = opening.context
        if (context == null) return JsonObject(decline(checkNotNull(opening.reason)) + buildJsonObject {
            put("cutoffClosed", opening.closed && !allowance.stop())
            put("snapshotFile", opening.file)
            put("totalIncludingInputMs", (System.nanoTime() - start) / 1_000_000.0)
            put("decisions", allowance.nodes.spent)
            put("visits", allowance.visits)
            put("cumulativeModeledBytes", allowance.bytes)
            put("originalSourceIncumbentChecked", opening.closed)
            put("stopped", allowance.stop())
        })
        val snapshot = context.snapshot
        val identity = ResidualSnapshots.encode(snapshot)
        if (!allowance.retain(identity.length * 12L) || !allowance.visit(identity.length.toLong())) {
            return decline("input-allowance")
        }
        ResidualSnapshots.verify(snapshot)
        val checker = originalChecker(context, study.receipts)
        val checked = checker(checkNotNull(snapshot.activeCutoff).witness, allowance)
        check(checked || allowance.stop())
        val verified = System.nanoTime()
        val result = if (allowance.stop()) decline("verification-allowance") else {
            run(Invocation(context, allowance, identity, checker))
        }
        return JsonObject(result + buildJsonObject {
            put("conditionIdentity", snapshot.conditionIdentity)
            put("snapshotFile", opening.file)
            put("loadVerifyMs", (verified - start) / 1_000_000.0)
            put("originalSourceIncumbentChecked", checked)
            put("osName", System.getProperty("os.name"))
            put("osArch", System.getProperty("os.arch"))
            put("javaVersion", System.getProperty("java.version"))
            put("totalIncludingInputMs", (System.nanoTime() - start) / 1_000_000.0)
            put("decisions", allowance.nodes.spent)
            put("visits", allowance.visits)
            put("cumulativeModeledBytes", allowance.bytes)
            put("stopped", allowance.stop())
        })
    }

    private fun decline(reason: String): JsonObject = buildJsonObject { put("decline", reason) }
}
