package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.linearRows
import com.eignex.klause.solver.pipeline.ComponentPlan
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.reflect.Method
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearDisjunctionInspection {
    fun inspect(engine: Any, stop: Cancellation, out: File) {
        val started = System.nanoTime()
        if (stop()) {
            skipped(out, "original-deadline-spent", stop)
            return
        }
        val preparation = field(engine, "preparation")
        if (preparation == null) {
            skipped(out, "cached-preparation-unavailable", stop)
            return
        }
        val model = member(preparation, "getModel") as? Problem
        val plan = member(preparation, "getPlan") as? ComponentPlan
        if (model == null || plan == null) {
            skipped(out, "cached-preparation-has-no-plan", stop)
            return
        }
        val fragment = field(plan, "installedFragment") as? Problem ?: plan.theoryFragment(model)
        val prepare = Class.forName("com.eignex.klause.theory.qflra.QfLiraSolverKt").declaredMethods.single {
            it.name.startsWith("prepareExactLiraSource") && it.parameterCount == 2
        }
        val factory = if (stop()) null else prepare.invoke(null, fragment, stop)
        val result = buildJsonObject {
            put("mode", "isolated-disjunction-naming")
            put("actualEarlierRegistrationsReproduced", false)
            put("actualReboundFragmentCaptured", false)
            put("fragment", "cached-preparation-installed-or-theory-fragment")
            put("predicateLayoutContract", "forObjectiveRound rebinds the installed fragment with its same factors")
            put("sourceVerdictClaim", false)
            put("solverInitialized", false)
            put("deadlineRenewed", false)
            put("fragmentBooleans", fragment.numBoolVars)
            put("fragmentIntegers", fragment.numIntVars)
            put("fragmentReals", fragment.numRealVars)
            put("fragmentFactors", fragment.factors.size)
            if (factory != null) {
                val open = factory.javaClass.methods.single {
                    it.name == "open" && it.parameterCount == 2
                }
                val component = open.invoke(factory, plan.cpIntVars, null)
                try {
                    val register = component.javaClass.declaredMethods.single {
                        it.name == "registerDisjunct" && it.parameterCount == 3
                    }.apply { isAccessible = true }
                    put("naming", replay(component, register, fragment, model.numBoolVars, stop))
                } finally {
                    (component as AutoCloseable).close()
                }
            } else {
                put("naming", "source-metadata-unavailable")
            }
            put("stoppedAtReturn", stop())
            put("elapsedNs", System.nanoTime() - started)
        }
        out.resolve("disjunction-naming.json").writeText(result.toString() + "\n")
        println(result)
    }

    private fun replay(
        component: Any, register: Method, fragment: Problem, sourceBooleans: Int, stop: Cancellation,
    ): JsonObject {
        val registry = SearchAtomRegistry(sourceBooleans)
        val context = SearchSession(emptyList(), cancellation = stop, atoms = registry)
        val address = register.parameterTypes[2].getDeclaredConstructor(Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        var visited = 0
        for ((factorIndex, factor) in fragment.factors.withIndex()) {
            if (stop()) break
            if (factor.linearForm !is LinearForm.Disjunction) continue
            for ((rowIndex, row) in factor.linearRows.withIndex()) {
                if (stop()) break
                val location = address.newInstance(factorIndex, rowIndex)
                visited++
                val stoppedBefore = stop()
                if (register.invoke(component, row, context, location) != null) continue
                val stoppedAfter = stop()
                val existing = assertions(registry)
                val scratch = SearchAtomRegistry(sourceBooleans)
                val scratchContext = SearchSession(emptyList(), cancellation = stop, atoms = scratch)
                val acceptedAlone = !stop() && register.invoke(component, row, scratchContext, location) != null
                return buildJsonObject {
                    put("visitedRows", visited)
                    put("registeredPairs", existing.size / 2)
                    put("capacity", field(registry, "maxAtoms") as Int)
                    put("stoppedBeforeRefusal", stoppedBefore)
                    put("stoppedAfterRefusal", stoppedAfter)
                    put("stoppedAfterScratch", stop())
                    put("factorIndex", factorIndex)
                    put("rowIndex", rowIndex)
                    put("refusedRow", rowRecord(row))
                    put("acceptedInEmptyRegistry", acceptedAlone)
                    put("pairComparison", comparePairs(registry, scratch))
                }
            }
        }
        return buildJsonObject {
            put("visitedRows", visited)
            put("registeredPairs", assertions(registry).size / 2)
            put("capacity", field(registry, "maxAtoms") as Int)
            put("stopped", stop())
            put("refusalObserved", false)
        }
    }

    private fun comparePairs(existing: SearchAtomRegistry, scratch: SearchAtomRegistry): JsonObject {
        val named = field(existing, "names") as Map<*, *>
        val previous = assertions(existing)
        val proposed = assertions(scratch)
        return buildJsonObject {
            put("proposed", buildJsonArray {
                for (entry in proposed) {
                    val matched = named[entry.payload] as? RegisteredTheoryDecision
                    add(buildJsonObject {
                        put("payload", payloadRecord(entry.payload))
                        put("existingLiteral", matched?.literal)
                        matched?.let {
                            val index = (it.literal xor 1) - existing.sourceBooleanCount * 2
                            put("existingComplement", payloadRecord(previous[index].payload))
                        }
                    })
                }
            })
        }
    }

    private fun rowRecord(row: LinearRow): JsonObject = buildJsonObject {
        put("relation", row.relation.name)
        put("strict", row.strict)
        put("activator", row.activator)
        put("size", row.size)
        put("references", buildJsonArray { for (i in 0 until row.size) add(JsonPrimitive(row.ref(i))) })
        when (val constants = row.constants) {
            is IntegralConstants -> {
                put("bound", constants.exactBound.toString())
                put("coefficients", buildJsonArray {
                    for (i in 0 until row.size) add(JsonPrimitive(constants.exactCoeff(i).toString()))
                })
            }
            is RealConstants -> {
                put("boundBits", constants.bound.toRawBits())
                put("discreteCoefficientBits", buildJsonArray {
                    for (i in 0 until constants.intCoefficients.size) {
                        add(JsonPrimitive(constants.intCoefficients.at(i).toRawBits()))
                    }
                })
                put("realCoefficientBits", buildJsonArray {
                    for (i in 0 until constants.realCoefficients.size) {
                        add(JsonPrimitive(constants.realCoefficients.at(i).toRawBits()))
                    }
                })
            }
        }
    }

    private fun payloadRecord(payload: Any): JsonObject = buildJsonObject {
        put("type", payload.javaClass.simpleName)
        if (payload is SourceBoundAtom) {
            put("threshold", payload.threshold.toString())
            put("upper", payload.upper)
            put("strict", payload.strict)
            put("terms", buildJsonArray {
                for (term in payload.terms) add(JsonPrimitive("${term.source}:${term.coefficient}"))
            })
        } else {
            put("description", payload.toString())
        }
    }

    private fun assertions(registry: SearchAtomRegistry): List<RegisteredTheoryDecision> =
        (field(registry, "assertions") as List<*>).map { it as RegisteredTheoryDecision }

    private fun skipped(out: File, reason: String, stop: Cancellation) {
        val result = buildJsonObject {
            put("mode", "isolated-disjunction-naming")
            put("status", "skipped")
            put("reason", reason)
            put("stoppedAtReturn", stop())
            put("sourceVerdictClaim", false)
            put("solverInitialized", false)
            put("deadlineRenewed", false)
        }
        out.resolve("disjunction-naming.json").writeText(result.toString() + "\n")
        println(result)
    }

    private fun member(owner: Any, name: String): Any? = owner.javaClass.methods
        .singleOrNull { it.name == name && it.parameterCount == 0 }?.invoke(owner)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}
