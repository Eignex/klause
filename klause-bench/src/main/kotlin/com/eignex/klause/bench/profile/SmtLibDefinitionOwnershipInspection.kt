package com.eignex.klause.bench.profile

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UNCHECKED_CAST")
internal object SmtLibDefinitionOwnershipInspection {
    private data class Owner(val variable: Int, val integer: Boolean)
    private class Snapshot(
        val owners: Map<Factor, Owner>,
        val retained: Set<Owner>,
        val integerOwners: Map<Int, Owner>,
        val booleanOwners: Map<Int, Owner>,
        val definitions: Map<Int, Any>,
        val lateImages: Map<Int, Int>,
    )

    fun compile(builder: Any, state: Any, canonical: SmtLibProblem, out: File): SmtLibProblem {
        require(field(builder, "objectiveSpec") == null && canonical.objective == null)
        require(canonical.model.numRealVars == 0 && canonical.realVarNames.isEmpty())
        extension("SmtLibBoundsKt", "inferBounds", builder)
        val boundClosing = extension("SmtLibBoundsKt", "hasOpenProductOperand", builder) == true
        if (boundClosing) extension("SmtLibBoundsKt", "closeProductBounds", builder)
        val assertions = field(builder, "asserts") as List<*>
        for (assertion in assertions) extension("SmtLibExprKt", "assert", builder, assertion)
        extension("SmtLibIteChainKt", "lowerOpenIteChains", builder)
        extension("SmtLibIteChainKt", "lowerOpenIteChains", builder)
        method(state, "expandPending", builder)
        val factors = field(builder, "factors") as MutableList<Factor>
        val intNames = field(builder, "intNames") as Map<String, Int>
        val boolNames = field(builder, "boolNames") as Map<String, Int>
        val originalSources = intNames.values.toSet()
        val originalFactors = factors.toList()
        val snapshot = snapshot(state, factors, intNames.values, boolNames.values)
        val removed = requireNotNull(method(state, "retainNeededDefinitions", factors, intNames.values, boolNames.values, null))
        val removedInts = field(removed, "ints") as Set<Int>
        val removedBools = field(removed, "bools") as Set<Int>
        require(removedInts == snapshot.integerOwners.filterValues { it !in snapshot.retained }.keys)
        require(removedBools == snapshot.booleanOwners.filterValues { it !in snapshot.retained }.keys)
        require(factors == originalFactors.filter { snapshot.owners[it]?.let { owner -> owner !in snapshot.retained } != true })
        require(method(builder, "compactConditionalColumns", removed, null) == null)
        val count = field(builder, "nextBool") as Int
        require(field(builder, "nextReal") == 0)
        val bounds = extension("SmtLibBoundsKt", "modelIntBounds", builder) as IntBounds
        val parsed = SmtLibProblem(
            Problem(settings = canonical.model.settings, numBoolVars = count, intBounds = bounds,
                factors = factors.toTypedArray()),
            objective = null, intVarNames = LinkedHashMap(intNames), boolVarNames = LinkedHashMap(boolNames),
        )
        require(parsed.intVarNames == canonical.intVarNames && parsed.boolVarNames == canonical.boolVarNames)
        SmtLibCompilationInspection.requireSameModel(parsed.model, canonical.model)
        val observedWork = field(state, "work")
        val report = report(snapshot, originalFactors, originalSources, removedInts, removedBools, boundClosing)
        require(field(state, "work") == observedWork)
        out.resolve("compiler-ownership.json").writeText(report.toString() + "\n")
        return parsed
    }

    private fun snapshot(state: Any, factors: List<Factor>, ints: Collection<Int>, bools: Collection<Int>): Snapshot {
        val definitions = field(state, "definitions") as Map<Int, Any>
        require(definitions.isNotEmpty()) { "ownership inspection requires conditional definitions" }
        val predicates = field(state, "booleanDefinitions") as Map<Int, List<Factor>>
        val work = field(state, "work")
        val lateImages = definitions.mapNotNull { (variable, definition) ->
            if (field(definition, "image") != null) return@mapNotNull null
            val image = method(state, "constantImage", field(definition, "arms"),
                field(definition, "default"), field(definition, "guardTests")) as? Set<*>
            require(field(definition, "image") == null)
            image?.let { variable to it.size }
        }.toMap()
        require(field(state, "work") == work)
        val owners = HashMap<Factor, Owner>()
        val integerOwners = definitions.keys.associateWith { Owner(it, integer = true) }
        val booleanOwners = HashMap<Int, Owner>()
        val parts = HashMap<Owner, List<Factor>>()
        fun own(owner: Owner, factors: List<Factor>, inputs: Set<Int> = emptySet()) {
            parts[owner] = factors
            for (factor in factors) {
                owners[factor] = owner
                if (factor is ReifiedFactor && factor.auxBoolVar !in inputs) booleanOwners[factor.auxBoolVar] = owner
            }
        }
        for ((variable, definition) in definitions) {
            val guards = field(definition, "guards") as List<Int>
            own(integerOwners.getValue(variable), field(definition, "factors") as List<Factor>,
                guards.mapTo(HashSet()) { Lit.variable(it) })
        }
        for ((variable, factors) in predicates) {
            val owner = Owner(variable, integer = false)
            booleanOwners[variable] = owner
            own(owner, factors)
        }
        for (factor in factors) {
            if (factor is ReifiedFactor && factor !in owners && factor.auxBoolVar !in booleanOwners) {
                own(Owner(factor.auxBoolVar, integer = false), listOf(factor))
            }
        }
        val retained = HashSet<Owner>()
        val pending = ArrayDeque<Owner>()
        fun need(owner: Owner?) {
            if (owner != null && retained.add(owner)) pending.addLast(owner)
        }
        fun read(factor: Factor) {
            factor.variables.ints.forEach { need(integerOwners[it]) }
            factor.variables.boolVars.forEach { need(booleanOwners[it]) }
        }
        ints.forEach { need(integerOwners[it]) }
        bools.forEach { need(booleanOwners[it]) }
        for (factor in factors) if (factor !in owners) read(factor)
        while (pending.isNotEmpty()) parts.getValue(pending.removeFirst()).forEach(::read)
        return Snapshot(owners, retained, integerOwners, booleanOwners, definitions, lateImages)
    }

    private fun report(
        snapshot: Snapshot,
        factors: List<Factor>,
        sources: Set<Int>,
        removedInts: Set<Int>,
        removedBools: Set<Int>,
        boundClosing: Boolean,
    ): JsonObject {
        val surviving = factors.filter { snapshot.owners[it]?.let { owner -> owner !in snapshot.retained } != true }
        val externalReads = surviving.filter { factor ->
            snapshot.owners[factor]?.integer != true && factor.variables.ints.any { it in snapshot.integerOwners }
        }
        val rows = externalReads.filterIsInstance<ReifiedLinear>()
        val retainedDefinitions = snapshot.integerOwners.filterValues { it in snapshot.retained }.keys
        val directlyRead = externalReads.flatMap { it.variables.ints.toList() }
            .filterTo(HashSet()) { it in snapshot.integerOwners }
        return buildJsonObject {
            put("observation", "staged-default-compiler-definition-ownership")
            put("canonicalModelVerified", true)
            put("actualRetentionClosureVerified", true)
            put("sourceVerdictClaim", false)
            put("additionalNumericalOperation", boundClosing)
            put("additionalCompilerBoundClosing", boundClosing)
            put("additionalNumericalOperationCount", "unmeasured")
            put("timingClaim", false)
            put("integerDefinitions", snapshot.integerOwners.size)
            put("retainedIntegerDefinitions", retainedDefinitions.size)
            put("removedIntegerDefinitions", removedInts.size)
            put("removedBooleanColumns", removedBools.size)
            put("retainedPredicateOwners", snapshot.retained.count { !it.integer })
            put("definitionsReadOutsideIntegerOwners", directlyRead.size)
            put("retainedKnownImageDefinitions", retainedDefinitions.count {
                field(snapshot.definitions.getValue(it), "image") != null
            })
            put("lateKnownImagesFromCurrentCompletedChildren", snapshot.lateImages.size)
            put("retainedLateKnownImages", retainedDefinitions.count { it in snapshot.lateImages })
            put("lateImageSizes", histogram(snapshot.lateImages.values.map(Int::toString)))
            put("lateImageScope", "one read-only call to existing constantImage per unknown definition; no fixpoint or mutation")
            put("retainedFactorKinds", histogram(surviving.map { it.javaClass.simpleName }))
            put("externalDefinitionReadFactorKinds", histogram(externalReads.map { it.javaClass.simpleName }))
            put("externalReifiedRows", histogram(rows.map { row ->
                "${row.op}/arity${row.vars.size}/definitions${row.vars.count { it in snapshot.integerOwners }}" +
                    "/sources${row.vars.count { it in sources }}"
            }))
            put("externalReifiedDefinitionImages", histogram(rows.map { row ->
                val images = row.vars.filter { it in snapshot.definitions }.map {
                    field(snapshot.definitions.getValue(it), "image") as? Set<*>
                }
                "${row.op}/arity${row.vars.size}/known${images.count { it != null }}/unknown${images.count { it == null }}"
            }))
            put("conditionalLeaves", SmtLibConditionalLeafInspection(snapshot.definitions, sources)
                .inspect(retainedDefinitions, directlyRead, rows))
            put("externalRowExamples", buildJsonArray {
                for (row in rows.take(32)) add(buildJsonObject {
                    put("operator", row.op.name)
                    put("bound", row.constants.exactBound.toString())
                    put("coefficients", buildJsonArray {
                        row.vars.indices.forEach { add(JsonPrimitive(row.constants.exactCoeff(it).toString())) }
                    })
                    put("columns", buildJsonArray { row.vars.forEach { add(JsonPrimitive(it)) } })
                    put("definitionColumns", buildJsonArray {
                        row.vars.filter { it in snapshot.integerOwners }.forEach { add(JsonPrimitive(it)) }
                    })
                    put("sourceColumns", buildJsonArray {
                        row.vars.filter { it in sources }.forEach { add(JsonPrimitive(it)) }
                    })
                    put("definitions", buildJsonArray {
                        for (variable in row.vars.filter { it in snapshot.definitions }) add(buildJsonObject {
                            val definition = snapshot.definitions.getValue(variable)
                            put("column", variable)
                            put("imageSize", (field(definition, "image") as? Set<*>)?.size)
                            put("guards", (field(definition, "guards") as List<*>).size)
                        })
                    })
                })
            })
            put("classificationScope", "precompaction owner identities; external reads exclude integer defining factors")
        }
    }

    private fun histogram(values: List<String>): JsonObject = buildJsonObject {
        for ((key, count) in values.groupingBy { it }.eachCount().toSortedMap()) put(key, count)
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun method(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods.single {
        it.name.substringBefore('$') == name && it.parameterCount == args.size
    }.apply { isAccessible = true }.invoke(owner, *args)

    private fun extension(file: String, name: String, builder: Any, vararg args: Any?): Any? =
        Class.forName("com.eignex.klause.formats.smtlib.$file").declaredMethods.single {
            it.name.substringBefore('$') == name && it.parameterCount == args.size + 1
        }.apply { isAccessible = true }.invoke(null, builder, *args)
}
