package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigInteger
import java.security.MessageDigest
import java.util.HexFormat

@Serializable
internal data class ResidualCutoff(
    val strictUpper: String,
    val witness: List<Boolean>,
    val provenance: String = "prepared-incumbent-witness",
)

@Serializable
internal data class ResidualRestrictionDocument(
    val storeIndex: Int,
    val permanentRetention: Boolean,
    val logicalScope: String,
    val conditionIdentity: String,
    val problem: JsonObject,
)

@Serializable
internal data class ResidualSnapshot(
    val version: Int,
    val sourceIdentity: JsonObject,
    val preparationIdentity: JsonObject,
    val preparedIdentity: String,
    val preparedProblem: JsonObject,
    val boolWeights: List<Long>,
    val objectiveConstant: Long,
    val values: List<Boolean?>,
    val openingAssumptions: List<Int>,
    val activeCutoff: ResidualCutoff?,
    val conditionIdentity: String,
    val learned: List<ResidualRestrictionDocument>,
    val residualToPrepared: List<Int>,
    val preparedToResidual: List<Int>,
    val residualClauses: List<List<Int>>,
    val sourceComponents: List<List<Int>>,
    val enforcingComponents: List<List<Int>>,
    val allEnforcingProblem: JsonObject,
    val fixedObjective: String,
    val isolatedObjective: String,
    val originalSourceReplay: String,
)

internal data class ResidualCapture(val bytes: ByteArray?, val decline: String?)

// Structural verification cannot establish preparation equivalence to the original source.
internal object ResidualSnapshots {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_COORDINATES = 100_000
    private const val SOURCE_REPLAY = "unavailable: preparation reconstruction not retained"
    private val json = Json { encodeDefaults = true }
    private val codec = ProblemJson()

    fun load(text: String): ResidualSnapshot {
        require(text.length <= MAX_BYTES && text.toByteArray().size <= MAX_BYTES) { "snapshot byte limit" }
        return json.decodeFromString<ResidualSnapshot>(text).also(::verify)
    }

    fun encode(snapshot: ResidualSnapshot): String = json.encodeToString(snapshot)

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun capture(
        prepared: Problem,
        objective: LinearObjective,
        values: List<Boolean?>,
        inspection: LearnedResidualInspection,
        sourceIdentity: JsonObject,
        preparationIdentity: JsonObject,
        cutoff: ResidualCutoff?,
        stop: Cancellation,
        maxBytes: Int,
    ): ResidualCapture {
        if (stop()) return ResidualCapture(null, "interrupted")
        if (!inspection.complete) return ResidualCapture(null, "incomplete-enforcing-restrictions")
        if (prepared.numIntVars != 0 || prepared.numRealVars != 0 || prepared.numBoolVars > MAX_COORDINATES ||
            prepared.factors.any { it !is Clause } || objective.intCoefficients.any { it != 0L } ||
            objective.realCoefficients.any { it != 0.0 }
        ) return ResidualCapture(null, "unsupported-model-or-objective")
        if (prepared.factors.size.toLong() + inspection.entries.size > maxBytes / 512L) {
            return ResidualCapture(null, "byte-limit")
        }
        val factors = prepared.factors.toList() + inspection.entries.map { requireNotNull(it.factor) }
        val terms = factors.sumOf { factor ->
            when (factor) {
                is Clause -> factor.literals.size.toLong()
                is PseudoBoolean -> factor.literals.size.toLong()
                else -> MAX_BYTES.toLong()
            }
        }
        val estimate = 8192L + 512L * factors.size + 160L * prepared.numBoolVars + 96L * terms +
            sourceIdentity.toString().length * 6L + preparationIdentity.toString().length * 6L
        if (maxBytes !in 1..MAX_BYTES || estimate > maxBytes) return ResidualCapture(null, "byte-limit")
        return runCatching {
            check(!stop()) { "interrupted" }
            val preparedDocument = codec.encodeToJsonElement(prepared)
            val sourceWeights = objective.boolWeights
            require(sourceWeights.size <= prepared.numBoolVars) { "objective coordinate mismatch" }
            val weights = List(prepared.numBoolVars) { sourceWeights.getOrElse(it) { 0L } }
            val identity = sha256(preparedDocument.toString())
            val condition = conditionIdentity(identity, weights, objective.constant, values, emptyList(), cutoff,
                sourceIdentity, preparationIdentity)
            val learned = inspection.entries.map { entry ->
                check(!stop()) { "interrupted" }
                val retained = model(prepared.numBoolVars, listOf(requireNotNull(entry.factor)))
                ResidualRestrictionDocument(
                    entry.storeIndex, entry.permanentRetention, "unavailable", condition,
                    codec.encodeToJsonElement(retained),
                )
            }
            val source = sourceClauses(prepared, values, stop)
            val mapping = values.indices.filter { values[it] == null }
            val inverse = List(values.size) { mapping.binarySearch(it).let { local -> if (local < 0) -1 else local } }
            val remapping = VarRemap(inverse.toIntArray(), IntArray(0))
            val all = enforcing(prepared, values, learned, weights, objective.constant, cutoff, stop)
            val fixed = fixedObjective(weights, objective.constant, values)
            val isolated = isolatedObjective(weights, values, source)
            val snapshot = ResidualSnapshot(
                1, sourceIdentity, preparationIdentity, identity, preparedDocument, weights, objective.constant,
                values.toList(), emptyList(), cutoff, condition, learned, mapping, inverse, source,
                components(source.map { clause -> clause.map(Lit::variable) }), components(all.map(::support)),
                codec.encodeToJsonElement(model(mapping.size, all.map { it.remap(remapping) })),
                fixed.toString(), isolated.toString(), SOURCE_REPLAY,
            )
            check(!stop()) { "interrupted" }
            val bytes = encode(snapshot).toByteArray()
            require(bytes.size <= maxBytes) { "byte-limit" }
            check(!stop()) { "interrupted" }
            ResidualCapture(bytes, null)
        }.getOrElse { ResidualCapture(null, it.message ?: it::class.simpleName) }
    }

    @Suppress("LongMethod")
    fun verify(snapshot: ResidualSnapshot) {
        require(snapshot.version == 1) { "unsupported snapshot version" }
        require(snapshot.originalSourceReplay == SOURCE_REPLAY) { "unsupported source replay authority" }
        val prepared = codec.decodeFromJsonElement(snapshot.preparedProblem)
        require(prepared.numIntVars == 0 && prepared.numRealVars == 0 && prepared.numBoolVars <= MAX_COORDINATES)
        require(prepared.factors.all { it is Clause }) { "unsupported source factor" }
        require(snapshot.boolWeights.size == prepared.numBoolVars && snapshot.values.size == prepared.numBoolVars)
        require(snapshot.preparedIdentity == sha256(snapshot.preparedProblem.toString())) {
            "prepared identity mismatch"
        }
        require(snapshot.openingAssumptions.isEmpty()) { "unsupported opening assumptions" }
        val condition = conditionIdentity(
            snapshot.preparedIdentity, snapshot.boolWeights, snapshot.objectiveConstant, snapshot.values,
            snapshot.openingAssumptions, snapshot.activeCutoff, snapshot.sourceIdentity, snapshot.preparationIdentity,
        )
        require(snapshot.conditionIdentity == condition) { "condition mismatch" }
        require(snapshot.learned.map { it.storeIndex } == snapshot.learned.indices.toList()) {
            "incomplete learned store"
        }
        require(snapshot.learned.all { it.logicalScope == "unavailable" && it.conditionIdentity == condition }) {
            "restriction scope mismatch"
        }
        val mapping = snapshot.values.indices.filter { snapshot.values[it] == null }
        require(snapshot.residualToPrepared == mapping) { "coordinate mapping mismatch" }
        val inverse = List(snapshot.values.size) { v ->
            mapping.binarySearch(v).let { local -> if (local < 0) -1 else local }
        }
        require(snapshot.preparedToResidual == inverse) { "inverse mapping mismatch" }
        val clauses = sourceClauses(prepared, snapshot.values, Cancellation.Never)
        require(snapshot.residualClauses == clauses) { "residual clauses mismatch" }
        require(snapshot.sourceComponents == components(clauses.map { clause -> clause.map(Lit::variable) })) {
            "source membership mismatch"
        }
        require(snapshot.fixedObjective == fixedObjective(
            snapshot.boolWeights, snapshot.objectiveConstant, snapshot.values,
        ).toString()) { "fixed objective mismatch" }
        require(snapshot.isolatedObjective == isolatedObjective(
            snapshot.boolWeights, snapshot.values, clauses,
        ).toString()) { "isolated objective mismatch" }
        val all = enforcing(prepared, snapshot.values, snapshot.learned, snapshot.boolWeights,
            snapshot.objectiveConstant, snapshot.activeCutoff, Cancellation.Never)
        require(snapshot.enforcingComponents == components(all.map(::support))) { "enforcing membership mismatch" }
        val remapping = VarRemap(inverse.toIntArray(), IntArray(0))
        val expected = model(mapping.size, all.map { it.remap(remapping) })
        require(codec.encodeToJsonElement(expected) == snapshot.allEnforcingProblem) { "enforcing residual mismatch" }
    }

    fun enforcingProblem(snapshot: ResidualSnapshot): Problem =
        codec.decodeFromJsonElement(snapshot.allEnforcingProblem)

    fun objective(weights: List<Long>, constant: Long, witness: List<Boolean>): BigInteger {
        require(weights.size == witness.size)
        return weights.indices.fold(BigInteger.valueOf(constant)) { total, v ->
            if (witness[v]) total + BigInteger.valueOf(weights[v]) else total
        }
    }

    fun components(scopes: List<List<Int>>): List<List<Int>> {
        val vertices = scopes.flatten().distinct().sorted()
        val parents = vertices.associateWith { it }.toMutableMap()
        fun root(v: Int): Int {
            var r = v
            while (parents.getValue(r) != r) r = parents.getValue(r)
            var x = v
            while (x != r) {
                val next = parents.getValue(x)
                parents[x] = r
                x = next
            }
            return r
        }
        for (scope in scopes) {
            val first = scope.firstOrNull() ?: continue
            for (v in scope) parents[root(v)] = root(first)
        }
        return vertices.groupBy(::root).values.map { it.sorted() }.sortedBy { it.first() }
    }

    private fun sourceClauses(prepared: Problem, values: List<Boolean?>, stop: Cancellation): List<List<Int>> =
        prepared.factors.mapNotNull { factor ->
            check(!stop()) { "interrupted" }
            val clause = factor as Clause
            require(clause.literals.all { Lit.variable(it) in values.indices }) { "unnameable source literal" }
            if (clause.literals.any { values[Lit.variable(it)] == Lit.isPositive(it) }) null else
                clause.literals.filter { values[Lit.variable(it)] == null }
        }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun enforcing(
        prepared: Problem,
        values: List<Boolean?>,
        learned: List<ResidualRestrictionDocument>,
        weights: List<Long>,
        constant: Long,
        cutoff: ResidualCutoff?,
        stop: Cancellation,
    ): List<Factor> {
        val factors = prepared.factors.toMutableList()
        for (entry in learned) {
            check(!stop()) { "interrupted" }
            val problem = codec.decodeFromJsonElement(entry.problem)
            require(problem.numBoolVars == values.size && problem.numIntVars == 0 && problem.numRealVars == 0)
            require(problem.factors.size == 1) { "restriction must retain one exact factor" }
            factors += problem.factors.single()
        }
        if (cutoff != null) {
            require(cutoff.provenance == "prepared-incumbent-witness") { "unknown cutoff provenance" }
            val upper = BigInteger(cutoff.strictUpper)
            require(upper == objective(weights, constant, cutoff.witness)) { "cutoff objective mismatch" }
            require(prepared.factors.all { f -> (f as Clause).literals.any {
                cutoff.witness[Lit.variable(it)] == Lit.isPositive(it)
            } }) { "cutoff witness violates prepared source" }
            val nonzero = weights.indices.filter { weights[it] != 0L }
            val positive = weights.filter { it > 0 }.fold(BigInteger.ZERO) { a, b -> a + BigInteger.valueOf(b) }
            factors += PseudoBoolean(
                Array(nonzero.size) { parseBigInt(BigInteger.valueOf(weights[nonzero[it]]).abs().toString()) },
                IntArray(nonzero.size) { Lit.make(nonzero[it], weights[nonzero[it]] < 0) }, PbOp.GE,
                parseBigInt((positive - (upper - BigInteger.valueOf(constant) - BigInteger.ONE)).toString()),
            )
        }
        return factors.mapNotNull { factor ->
            check(!stop()) { "interrupted" }
            when (factor) {
                is Clause -> {
                    require(factor.literals.all { Lit.variable(it) in values.indices })
                    if (factor.literals.any { values[Lit.variable(it)] == Lit.isPositive(it) }) null else
                        Clause(factor.literals.filter { values[Lit.variable(it)] == null }.toIntArray())
                }
                is PseudoBoolean -> residualPb(factor, values)
                else -> error("unsupported learned kind")
            }
        }
    }

    private fun residualPb(pb: PseudoBoolean, values: List<Boolean?>): PseudoBoolean? {
        require(pb.op == PbOp.GE) { "unsupported learned PB operator" }
        require(pb.literals.all { Lit.variable(it) in values.indices }) { "unnameable PB literal" }
        val constants = pb.constants
        var degree = BigInteger(constants.exactBound.toString())
        val free = ArrayList<Int>()
        for (i in pb.literals.indices) {
            val weight = BigInteger(constants.exactCoeff(i).toString())
            require(weight.signum() >= 0) { "unsupported signed PB coefficient" }
            when (values[Lit.variable(pb.literals[i])]) {
                null -> if (weight.signum() != 0) free += i
                Lit.isPositive(pb.literals[i]) -> degree -= weight
                else -> Unit
            }
        }
        if (degree.signum() <= 0) return null
        return PseudoBoolean(Array(free.size) { constants.exactCoeff(free[it]) },
            IntArray(free.size) { pb.literals[free[it]] }, PbOp.GE, parseBigInt(degree.toString()))
    }

    private fun fixedObjective(weights: List<Long>, constant: Long, values: List<Boolean?>): BigInteger =
        values.indices.fold(BigInteger.valueOf(constant)) { total, v ->
            if (values[v] == true) total + BigInteger.valueOf(weights[v]) else total
        }

    private fun isolatedObjective(weights: List<Long>, values: List<Boolean?>, clauses: List<List<Int>>): BigInteger {
        val used = clauses.flatten().map(Lit::variable).toSet()
        return values.indices.filter { values[it] == null && it !in used && weights[it] < 0 }
            .fold(BigInteger.ZERO) { total, v -> total + BigInteger.valueOf(weights[v]) }
    }

    private fun support(factor: Factor): List<Int> = when (factor) {
        is Clause -> factor.literals.map(Lit::variable).distinct().sorted()
        is PseudoBoolean -> factor.literals.map(Lit::variable).distinct().sorted()
        else -> error("unsupported residual factor")
    }

    private fun model(bools: Int, factors: List<Factor>): Problem =
        Problem(bools, 0, emptyArray(), factors.toTypedArray())

    @Suppress("LongParameterList")
    private fun conditionIdentity(
        prepared: String,
        weights: List<Long>,
        constant: Long,
        values: List<Boolean?>,
        opening: List<Int>,
        cutoff: ResidualCutoff?,
        source: JsonObject,
        preparation: JsonObject,
    ): String = sha256(
        listOf(prepared, weights, constant, values, opening, cutoff, source, preparation).joinToString("\n"),
    )

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
