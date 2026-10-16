package com.eignex.klause.bench.profile

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.formats.smtlib.SExprReader
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SmtLibCompilationInspection {
    fun inspect(text: String, canonical: SmtLibProblem, out: File, ownership: Boolean = false) {
        val stateType = Class.forName("com.eignex.klause.formats.smtlib.SmtLibConditionalEquality")
        val state = stateType.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(65_536)
        val builderType = Class.forName("com.eignex.klause.formats.smtlib.Compiler").declaredClasses
            .single { it.simpleName == "Builder" }
        val constructor = builderType.declaredConstructors.single { it.parameterCount == 6 }
        val builder = constructor.apply { isAccessible = true }.newInstance(
            DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI, false,
            canonical.model.settings, state, Cancellation.Never,
        )
        val command = builderType.declaredMethods.single {
            it.name.substringBefore('$') == "command" && it.parameterCount == 1
        }.apply { isAccessible = true }
        val reader = SExprReader(text)
        while (true) command.invoke(builder, reader.readCommandOrNull() ?: break)
        val parsed = if (ownership) SmtLibDefinitionOwnershipInspection.compile(builder, state, canonical, out)
        else builderType.declaredMethods.single {
            it.name.substringBefore('$') == "build" && it.parameterCount == 0
        }.apply { isAccessible = true }.invoke(builder) as SmtLibProblem
        require(parsed.objective == null && canonical.objective == null)
        require(parsed.intVarNames == canonical.intVarNames && parsed.boolVarNames == canonical.boolVarNames &&
            parsed.realVarNames == canonical.realVarNames) { "source names or mappings differ" }
        requireSameModel(parsed.model, canonical.model)
        val definitions = field(state, "definitions") as Map<*, *>
        val comparisonField = if (stateType.declaredFields.any { it.name == "comparisons" }) "comparisons"
            else "equalities"
        val comparisons = field(state, comparisonField) as Map<*, *>
        val byOperator = comparisons.keys.groupingBy { key ->
            val comparison = requireNotNull(key)
            if (comparison.javaClass.declaredFields.any { it.name == "op" }) field(comparison, "op") as String else "="
        }.eachCount()
        val guards = definitions.values.map { (field(requireNotNull(it), "guards") as List<*>).size }
        val images = definitions.values.map { field(requireNotNull(it), "image") as? Set<*> }
        val limit = field(state, "workLimit") as Int
        val work = field(state, "work") as Int
        require(limit == 65_536)
        val sourceColumns = canonical.intVarNames.values.toHashSet()
        val rows = canonical.model.factors.filterIsInstance<ReifiedLinear>()
            .groupingBy { row ->
                "${row.op}/${row.vars.size}/" + if (row.vars.any { it !in sourceColumns }) "auxiliary" else "source"
            }.eachCount()
        val report = buildJsonObject {
            put("observation", "separate-default-frontend-compilation-after-build")
            put("canonicalModelVerified", true)
            put("sourceVerdictClaim", false)
            put("cliRoutingEquivalence", "unverified")
            put("additionalCompilationBeforeOpeningDeadline", true)
            val boundClosing = field(builder, "productBoundsClosed") == true
            put("additionalNumericalOperation", boundClosing)
            put("additionalCompilerBoundClosing", boundClosing)
            put("additionalNumericalOperationCount", "unmeasured")
            put("traversalAdvanced", false)
            put("timingClaim", false)
            put("workLimit", limit)
            put("workUsed", work)
            put("atOrBeyondVisitLimit", work >= limit)
            put("definitions", definitions.size)
            put("unguardedDefinitions", guards.count { it == 0 })
            put("singleGuardDefinitions", guards.count { it == 1 })
            put("multipleGuardDefinitions", guards.count { it > 1 })
            put("knownImages", images.count { it != null })
            put("largestImage", images.mapNotNull { it?.size }.maxOrNull())
            put("cachedComparisons", comparisons.size)
            put("cachedComparisonsByOperator", buildJsonObject {
                for ((op, count) in byOperator.toSortedMap()) put(op, count)
            })
            val pendingField = if (comparisonField == "comparisons") "pendingComparisons" else "pendingEqualities"
            put("pendingComparisonsAfterBuild", (field(state, pendingField) as Map<*, *>).size)
            if (stateType.declaredFields.any { it.name == "pendingOrder" }) {
                put("pendingQueueAfterBuild", (field(state, "pendingOrder") as Collection<*>).size)
            }
            put("compiledIntegers", canonical.model.numIntVars)
            put("compiledBooleans", canonical.model.numBoolVars)
            put("compiledFactors", canonical.model.factors.size)
            put("sourceIntegers", sourceColumns.size)
            put("reifiedRowsByOperatorArityAndColumnOrigin", buildJsonObject {
                for ((kind, count) in rows.toSortedMap()) put(kind, count)
            })
            put("rowClassificationScope", "includes defining rows and selection channels; no ownership attribution")
        }
        out.resolve("compiler-expansion.json").writeText(report.toString() + "\n")
    }

    internal fun requireSameModel(left: Problem, right: Problem) {
        require(left.numIntVars == right.numIntVars && left.numBoolVars == right.numBoolVars &&
            left.numRealVars == right.numRealVars && left.factors.size == right.factors.size)
        require(left.realLower.contentEquals(right.realLower) && left.realUpper.contentEquals(right.realUpper))
        for (variable in 0 until left.numIntVars) {
            val a = left.intBounds
            val b = right.intBounds
            require(a.hasLower(variable) == b.hasLower(variable) && a.hasUpper(variable) == b.hasUpper(variable))
            if (a.hasLower(variable)) require(a.lower(variable) == b.lower(variable))
            if (a.hasUpper(variable)) require(a.upper(variable) == b.upper(variable))
            val first = left.intDomainOrNull(variable)
            val second = right.intDomainOrNull(variable)
            require((first == null) == (second == null))
            if (first != null && second != null) {
                require(first.holeCount == 0L && second.holeCount == 0L) { "diagnostic requires contiguous domains" }
                require(first.min == second.min && first.max == second.max)
            }
        }
        require(left.factors.indices.all { left.factors[it].structuralKey() == right.factors[it].structuralKey() }) {
            "ordered source factor keys differ"
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
