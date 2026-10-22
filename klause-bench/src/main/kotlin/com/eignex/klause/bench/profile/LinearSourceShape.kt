package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearSourceShape {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (args.size == 1 && args[0].startsWith("batch64=")) {
            val bytes = Base64.getDecoder().decode(args[0].substringAfter('='))
            require(bytes.size <= 32 * 1024)
            val requests = Json.decodeFromString<List<List<String>>>(bytes.decodeToString())
            require(requests.size in 1..8)
            requests.forEachIndexed { index, request -> inspect(request.toTypedArray(), out.resolve("$index")) }
        } else inspect(args, out)
    }

    private fun inspect(args: Array<String>, out: File) {
        require(args.size == 3) { "Use suite=ID problem=NAME sha256=HEX" }
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val expected = options.getValue("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024 && sha256(bytes) == expected)
        out.mkdirs()
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val started = System.nanoTime()
        val admitted = SmtLib.parse(bytes.decodeToString(), cancellation = Cancellation.after(10.seconds))
        val parsed = admitted
        val parsingNs = System.nanoTime() - started
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            parsed.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val model = requireNotNull(invocation.preparedPlan?.model) { "source has no factor inspection view" }
        val exactRows = model.factors.filterIsInstance<ExactLinear>()
        val activatedRows = exactRows.filter { it.activator != LinearRow.ALWAYS }
        val realSelections = model.factors.filterIsInstance<RealElement>()
        val binarySelectionIndexes = realSelections.filter { it.indexOffset == 0 && it.size == 2 }
            .map { it.idx }.toSet()
        val integerSelections = model.factors.filterIsInstance<Element>()
        val binaryIntegerSelectionIndexes = integerSelections.filter { it.indexOffset == 0 && it.arr.size == 2 }
            .map { it.idx }.toSet()
        val constantBinaryTables = integerSelections.mapNotNull { constantBinaryTable(model, it) }
        val guardSelectors = selectionGuards(model, binarySelectionIndexes)
        val predicates = HashSet<StructuralKey>()
        for (row in exactRows) {
            val constants = row.constants
            predicates += ExactLinear.of(
                IntArray(row.size, row::ref),
                List(row.size) { BigFraction.of(constants.exactCoeff(it), BIG_ONE) },
                row.relation, BigFraction.of(constants.exactBound, BIG_ONE), row.strict,
            ).structuralKey()
        }
        val shape = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", expected)
            put("sourceBytes", bytes.size)
            put("suite", options.getValue("suite"))
            put("problem", options.getValue("problem"))
            put("scope", "linear-source-shape-only")
            put("solverExecuted", false)
            put("sourceVerdictClaim", false)
            put("parsingNs", parsingNs)
            put("sourceBooleanNames", parsed.boolVarNames.size)
            put("sourceIntegerNames", parsed.intVarNames.size)
            put("sourceRealNames", parsed.realVarNames.size)
            put("compiledBooleans", model.numBoolVars)
            put("compiledIntegers", model.numIntVars)
            put("compiledReals", model.numRealVars)
            put("compiledFactors", model.factors.size)
            put("booleanGateShapes", booleanGateShapes(model, parsed.boolVarNames.values))
            put("exactPredicateFamilies", exactPredicateShapes(model, parsed.boolVarNames.values))
            put("realSelections", realSelections.size)
            put("integerSelections", integerSelections.size)
            put("constantIntegerSelections", integerSelections.count { !it.arrIsVars })
            put("resolvedConstantBinaryIntegerSelections", constantBinaryTables.size)
            put("booleanValueIntegerSelections", constantBinaryTables.count {
                (it[0] == 0L && it[1] == 1L) || (it[0] == 1L && it[1] == 0L)
            })
            put("equalEntryBinaryIntegerSelections", constantBinaryTables.count { it[0] == it[1] })
            put("unitStepBinaryIntegerSelections", constantBinaryTables.count {
                (it[0] != Long.MAX_VALUE && it[1] == it[0] + 1L) ||
                    (it[1] != Long.MAX_VALUE && it[0] == it[1] + 1L)
            })
            put("integerSelectionSelectors", selectionShape(model, binaryIntegerSelectionIndexes))
            put("combinedSelectionSelectors",
                selectionShape(model, binarySelectionIndexes + binaryIntegerSelectionIndexes))
            put("binaryRealSelectionIndexes", binarySelectionIndexes.size)
            put("singleGuardBinarySelectionIndexes", guardSelectors.values.sumOf { it.size })
            put("distinctBinarySelectionGuards", guardSelectors.size)
            put("duplicateBinarySelectionIndexes", guardSelectors.values.sumOf { it.size - 1 })
            put("maxBinarySelectionIndexesPerGuard", guardSelectors.values.maxOfOrNull { it.size } ?: 0)
            put("binarySelectionGuardFanoutHistogram", buildJsonObject {
                guardSelectors.values.groupingBy { it.size }.eachCount().toSortedMap()
                    .forEach { (fanout, count) -> put(fanout.toString(), count) }
            })
            put("selectionEquivalence",
                "Prepared binary RealElement indexes in 0..1 with one unit reified EQ/NE definition at 1; " +
                    "same signed guard only")
            put("factorKinds", buildJsonObject {
                model.factors.groupingBy { it::class.simpleName ?: "Factor" }.eachCount().toSortedMap()
                    .forEach { (kind, count) -> put(kind, count) }
            })
            put("exactRows", exactRows.size)
            put("exactRowTerms", exactRows.sumOf { it.size.toLong() })
            put("activatedExactRows", activatedRows.size)
            put("activatedExactRowTerms", activatedRows.sumOf { it.size.toLong() })
            put("rawExactPredicateEntries", model.numBoolVars.toLong() + model.numIntVars + model.numRealVars +
                activatedRows.sumOf { it.size.toLong() + 1L })
            put("entryScope", "Raw source coordinates and activated exact rows; not an internal admission trace")
            put("constantExactRows", exactRows.count { it.size == 0 })
            put("unconditionalExactRows", exactRows.count { it.activator == LinearRow.ALWAYS })
            put("distinctExactPredicatesIgnoringActivators", predicates.size)
            put("predicateEquivalence",
                "ExactLinear structural identity after removing activators; no sign or implication reasoning")
            put("javaVersion", System.getProperty("java.version"))
            put("jvmOptions", buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            })
        }
        out.resolve("shape.json").writeText(shape.toString() + "\n")
        println(shape)
    }

    private fun selectionGuards(model: Problem, indexes: Set<Int>): Map<Int, List<Int>> {
        val definitions = model.factors.filterIsInstance<ReifiedLinear>().mapNotNull { row ->
            val constants = row.integerConstants ?: return@mapNotNull null
            if (row.vars.size != 1 || constants.coeff(0) != 1L || constants.bound != 1L ||
                row.op !in setOf(LinearOp.EQ, LinearOp.NE)
            ) return@mapNotNull null
            val column = row.vars[0]
            if (column !in indexes || !model.intBounds.hasLower(column) || !model.intBounds.hasUpper(column) ||
                model.intBounds.lower(column) != 0L || model.intBounds.upper(column) != 1L
            ) return@mapNotNull null
            column to Lit.make(row.auxBoolVar, positive = row.op == LinearOp.EQ)
        }.groupBy({ it.first }, { it.second })
        return definitions.filterValues { it.size == 1 }.entries.groupBy({ it.value.single() }, { it.key })
    }

    private fun constantBinaryTable(model: Problem, selection: Element): LongArray? {
        val bounds = model.intBounds
        if (selection.indexOffset != 0 || selection.arr.size != 2 || !bounds.hasLower(selection.idx) ||
            !bounds.hasUpper(selection.idx) || bounds.lower(selection.idx) < 0L || bounds.upper(selection.idx) > 1L
        ) return null
        if (!selection.arrIsVars) return selection.arr
        return LongArray(2) { position ->
            val column = selection.arr[position].toInt()
            if (!bounds.hasLower(column) || !bounds.hasUpper(column) || bounds.lower(column) != bounds.upper(column)) {
                return null
            }
            bounds.lower(column)
        }
    }

    private fun selectionShape(model: Problem, indexes: Set<Int>): JsonObject {
        val guards = selectionGuards(model, indexes)
        return buildJsonObject {
            put("binaryIndexes", indexes.size)
            put("singleGuardIndexes", guards.values.sumOf { it.size })
            put("distinctGuards", guards.size)
            put("duplicateIndexes", guards.values.sumOf { it.size - 1 })
            put("maxIndexesPerGuard", guards.values.maxOfOrNull { it.size } ?: 0)
            put("guardFanoutHistogram", buildJsonObject {
                guards.values.groupingBy { it.size }.eachCount().toSortedMap()
                    .forEach { (fanout, count) -> put(fanout.toString(), count) }
            })
        }
    }

    private fun exactPredicateShapes(model: Problem, declaredBooleans: Collection<Int>): JsonObject {
        val active = model.factors.filterIsInstance<ExactLinear>().filter { it.activator != LinearRow.ALWAYS }
        val owners = active.groupingBy { it.activator }.eachCount()
        val declared = declaredBooleans.toHashSet()
        val eligible = active.filter {
            owners[it.activator] == 1 && it.activator !in declared && it.variables.reals.isNotEmpty()
        }
        val groups = List(3) { HashMap<List<String>, MutableSet<Int>>() }
        for (row in eligible) for (mode in groups.indices) {
            val (key, negated) = predicateShape(row, primitive = mode > 0, complement = mode == 2)
            val head = if (mode == 2) Lit.make(row.activator, positive = !negated) else row.activator
            groups[mode].getOrPut(key) { HashSet() }.add(head)
        }
        return buildJsonObject {
            put("scope", "Private single-row active real comparisons; inferred families, not compiler cache hits")
            put("activatedRows", active.size)
            put("eligiblePrivateHeads", eligible.size)
            put("rawDistinctFunctions", groups[0].size)
            put("scaledDistinctFunctions", groups[1].size)
            put("complementFoldedFunctions", groups[2].size)
            put("rawDuplicateHeads", eligible.size - groups[0].size)
            put("additionalScaledDuplicates", groups[0].size - groups[1].size)
            put("additionalComplementDuplicates", groups[1].size - groups[2].size)
            put("maxScaledHeads", groups[1].values.maxOfOrNull { it.size } ?: 0)
            put("maxEquivalentOrComplementHeads", groups[2].values.maxOfOrNull { it.size } ?: 0)
            put("identity", "Exact coefficient/rhs gcd and vector orientation; inequality complements flip strictness")
        }
    }

    private fun predicateShape(row: ExactLinear, primitive: Boolean, complement: Boolean): Pair<List<String>, Boolean> {
        val constants = row.constants
        val ordered = (0 until row.size).map { row.ref(it) to constants.exactCoeff(it) }.sortedBy { it.first }
        require(ordered.isNotEmpty() && ordered.any { Term.isReal(it.first) })
        var divisor = if (primitive) constants.exactBound.abs() else BIG_ONE
        if (primitive) for ((_, coefficient) in ordered) divisor = divisor.gcd(coefficient)
        var relation = row.relation
        if (ordered.first().second.signum() < 0) {
            divisor = divisor.negate()
            relation = when (relation) {
                LinearOp.LE -> LinearOp.GE
                LinearOp.GE -> LinearOp.LE
                else -> relation
            }
        }
        var strict = row.strict
        var negated = false
        if (complement) when (relation) {
            LinearOp.GE -> {
                relation = LinearOp.LE
                strict = !strict
                negated = true
            }
            LinearOp.NE -> {
                relation = LinearOp.EQ
                negated = true
            }
            else -> Unit
        }
        val key = listOf(relation.name, strict.toString(), constants.exactBound.divide(divisor).toString()) +
            ordered.map { (reference, coefficient) -> "$reference:${coefficient.divide(divisor)}" }
        return key to negated
    }

    private fun booleanGateShapes(model: Problem, declaredBooleans: Collection<Int>): JsonObject {
        val clauses = model.factors.filterIsInstance<Clause>()
        val binary = clauses.filter { it.literals.size == 2 }.map { it.literals.sorted() }.toSet()
        val ternary = clauses.filter { it.literals.size == 3 }.map { it.literals.sorted() }
            .filter { row -> row.map(Lit::variable).distinct().size == 3 }.toSet()
        val definitions = HashMap<Pair<String, List<Int>>, MutableSet<Int>>()
        fun record(operator: String, inputs: List<Int>, head: Int) {
            definitions.getOrPut(operator to inputs) { HashSet() } += Lit.variable(head)
        }
        for (row in ternary) for (position in row.indices) {
            val head = row[position]
            val inputs = row.filterIndexed { index, _ -> index != position }
            val left = inputs[0]
            val right = inputs[1]
            if (listOf(Lit.negate(head), Lit.negate(left)).sorted() in binary &&
                listOf(Lit.negate(head), Lit.negate(right)).sorted() in binary
            ) {
                if (Lit.isPositive(head)) record("and", inputs.map(Lit::negate).sorted(), head)
                else record("or", inputs.sorted(), head)
            }
            val result = Lit.make(Lit.variable(head), true)
            for (other in listOf(right, Lit.negate(right))) {
                val clausesForIff = listOf(
                    listOf(Lit.negate(result), Lit.negate(left), other),
                    listOf(Lit.negate(result), left, Lit.negate(other)),
                    listOf(result, Lit.negate(left), Lit.negate(other)),
                    listOf(result, left, other),
                )
                if (clausesForIff.all { it.sorted() in ternary }) {
                    val ordered = listOf(left, other).sorted()
                    val canonical = if (Lit.isPositive(ordered[0])) ordered else ordered.map(Lit::negate)
                    record("iff", canonical, result)
                }
            }
        }
        val externalHeads = declaredBooleans.toMutableSet()
        for (factor in model.factors) if (factor !is Clause) externalHeads += factor.variables.boolVars.toList()
        return buildJsonObject {
            put("scope", "Complete CNF gate families in the inspected model; inferred definitions, not cache hits")
            put("iffIdentity", "Commutative signed inputs with simultaneous negation normalized")
            for (operator in listOf("and", "or", "iff")) {
                val groups = definitions.filterKeys { it.first == operator }.values
                val privateGroups = groups.map { heads -> heads.filter { it !in externalHeads } }
                    .filter { it.isNotEmpty() }
                put(operator, buildJsonObject {
                    put("completeDefinitionEntries", groups.sumOf { it.size })
                    put("distinctOutputHeads", groups.flatten().toSet().size)
                    put("distinctInputSignatures", groups.size)
                    put("duplicateEquivalentHeadEntries", groups.sumOf { it.size - 1 })
                    put("maxEquivalentHeads", groups.maxOfOrNull { it.size } ?: 0)
                    put("purePrivateDefinitionEntries", privateGroups.sumOf { it.size })
                    put("purePrivateInputSignatures", privateGroups.size)
                    put("purePrivateDuplicateHeadEntries", privateGroups.sumOf { it.size - 1 })
                })
            }
        }
    }
}
