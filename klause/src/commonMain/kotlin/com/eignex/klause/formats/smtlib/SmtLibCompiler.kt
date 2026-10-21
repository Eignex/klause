package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lowering.CnfLowering
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

/** Reject an unsupported construct with a clean [UnsupportedSmtException]. */
internal fun smtUnsupported(msg: String): Nothing = throw UnsupportedSmtException(msg)

/** One lowered linear relation `Σ coeffs·vars ⟨op⟩ bound` (shared by bound inference and lowering). */
internal data class Rel(val vars: IntArray, val coeffs: LongArray, val op: LinearOp, val bound: Long)

/** Compiler for the supported SMT-LIB linear-arithmetic subset (QF_LIA / QF_LRA / QF_LIRA
 *  fragments). The [Builder]'s per-concern compilation steps live in sibling files as extension
 *  functions: bound inference in `SmtLibBounds.kt`, boolean / assert / distinct compilation in
 *  `SmtLibExpr.kt`, linear-term / relation / objective lowering in `SmtLibLinear.kt`, and the real
 *  (LRA) lowering in `SmtLibReal.kt`. */
internal object Compiler {
    /** Mutable compilation state for one SMT-LIB parse. The heavy compilation logic is attached as
     *  `internal fun Compiler.Builder.…` extension functions in the sibling `SmtLib*.kt` files. */
    internal class Builder(
        val unboundedIntLo: Long,
        val unboundedIntHi: Long,
        val strictBounds: Boolean,
        internal val settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        internal val conditionalEqualities: SmtLibConditionalEquality = SmtLibConditionalEquality(),
        internal val sourceCancellation: Cancellation = Cancellation.Never,
        internal val semantics: EffectiveSourceSemantics = resolveSmtLibSemantics(unboundedIntLo, unboundedIntHi),
    ) :
        CnfLowering {
        internal val boolNames = HashMap<String, Int>()
        internal val intNames = HashMap<String, Int>()
        internal val realNames = HashMap<String, Int>()
        internal var nextBool = 0
        internal var nextInt = 0
        internal var nextReal = 0
        internal var inferringBounds = false
        internal val boundPremises = ArrayList<Rel>()
        internal var productBoundsClosed = false

        internal val intDomains = ArrayList<PresolveDomain>()
        internal val divModTerms = HashMap<SmtDivModKey, Pair<IntComb, IntComb>>()
        internal val realPredicates = SmtRealPredicates(sourceCancellation)
        internal val affineRealTables = SmtLibAffineRealTables(sourceCancellation)

        /** Open `ite`-on-equality chains and the equality atoms their conditions are read from. */
        internal val iteChains = IteChainTable()

        override val factors = ArrayList<Factor>()
        internal val asserts = ArrayList<SExpr>()
        internal var objectiveSpec: Pair<SExpr, Boolean>? = null // (term, negate)
        override var trueLitCache: Int = -1

        /** Non-recursive `define-fun` macros: name to (parameter names, body term, Bool-return flag).
         *  A call `(f a…)` is inlined by binding the parameters to the arguments like a `let`. */
        internal val macros = HashMap<String, Macro>()

        internal class Binding(val isBool: Boolean, val isReal: Boolean = false) {
            var lin: IntComb? = null
            var lit: Int? = null
            var real: RealComb? = null

            // Delay reification so asserted bindings can retain hard source relations
            // without an auxiliary Boolean definition.
            var srcBool: SExpr? = null
            var sourceScope: BindingSnapshot? = null
        }

        // Let scoping as a heap-allocated stack, not recursion. `bindingStacks` maps each name to its
        // shadow stack (innermost binding on top) for O(1) lookup; `scopeNames` records the names bound
        // by each active scope so it can be popped. A deeply nested formula unwinds its `let` chain
        // iteratively into these structures instead of recursing through the call stack.
        internal class ScopedBinding(val depth: Int, val binding: Binding)
        // Deferred Boolean values exclude their simultaneous peers and later caller-local shadows.
        internal class BindingSnapshot(val scope: BindingScope, val depth: Int)
        internal class BindingScope(val parent: BindingSnapshot? = null) {
            val bindingStacks = HashMap<String, ArrayDeque<ScopedBinding>>()
            val scopeNames = ArrayDeque<List<String>>()

            fun snapshot(): BindingSnapshot = BindingSnapshot(this, scopeNames.size)

            fun lookup(name: String): Binding? {
                var current: BindingScope? = this
                var depth = scopeNames.size
                while (current != null) {
                    val stack = current.bindingStacks[name]
                    if (stack != null) {
                        if (stack.last().depth <= depth) return stack.last().binding
                        var lower = 0
                        var upper = stack.lastIndex
                        while (lower <= upper) {
                            val middle = lower + (upper - lower) / 2
                            if (stack[middle].depth <= depth) lower = middle + 1 else upper = middle - 1
                        }
                        if (upper >= 0) return stack[upper].binding
                    }
                    val inherited = current.parent
                    current = inherited?.scope
                    depth = inherited?.depth ?: 0
                }
                return null
            }
        }
        internal var bindingScope = BindingScope()
        internal fun lookup(name: String): Binding? = bindingScope.lookup(name)

        /** The literal for a Bool binding, compiling its held term on the first ask. */
        internal fun boolLit(b: Binding): Int {
            b.lit?.let { return it }
            val src = b.srcBool ?: throw UnsupportedSmtException("Bool binding has no value")
            val caller = bindingScope
            bindingScope = BindingScope(checkNotNull(b.sourceScope))
            return try {
                compileBool(src).also {
                    b.lit = it
                    b.srcBool = null
                    b.sourceScope = null
                }
            } finally {
                bindingScope = caller
            }
        }

        /** Compile one `let`'s bindings (in parallel — their values don't see each other) and push them
         *  as a new scope. */
        internal fun pushLetScope(bindingList: SExpr) {
            val list = bindingList as? SExpr.SList ?: throw UnsupportedSmtException("malformed let bindings")
            val sourceScope = bindingScope.snapshot()
            pushScopeBindings(
                list.items.map { pair ->
                    val p = pair as? SExpr.SList ?: throw UnsupportedSmtException("malformed let binding")
                    val name = p.atomAt(0, "let binding name")
                    val expr = p.argAt(1, "let binding value")
                    val b = Binding(isBool = isBoolExpr(expr), isReal = !isBoolExpr(expr) && isRealExpr(expr))
                    when {
                        b.isBool -> {
                            b.srcBool = expr
                            b.sourceScope = sourceScope
                        }
                        b.isReal -> b.real = realTerm(expr)
                        else -> b.lin = linearTerm(expr)
                    }
                    name to b
                },
            )
        }

        /** Push already-compiled [bound] bindings as one new innermost scope. Used by the iterative
         *  term evaluator, which folds a `let`'s binding values in-stack before building the scope. */
        internal fun pushScopeBindings(bound: List<Pair<String, Binding>>) {
            val names = ArrayList<String>(bound.size)
            val depth = bindingScope.scopeNames.size + 1
            for ((name, b) in bound) {
                // A value reachable by name can be referenced any number of times, so an `ite` chain it
                // holds is finished: nothing can extend it any more, and it must define its variable.
                (b.lin as? IntComb.Narrow)?.lin?.asSimpleVar()?.let { closeIteChain(it) }
                bindingScope.bindingStacks.getOrPut(name) { ArrayDeque() }.addLast(ScopedBinding(depth, b))
                names.add(name)
            }
            bindingScope.scopeNames.addLast(names)
        }

        internal fun popLetScope() {
            for (name in bindingScope.scopeNames.removeLast()) {
                val stack = bindingScope.bindingStacks.getValue(name)
                stack.removeLast()
                if (stack.isEmpty()) bindingScope.bindingStacks.remove(name)
            }
        }

        override fun newBool(): Int = nextBool++
        internal fun newInt(): Int {
            val bounds = requireNotNull(semantics.effective.undeclaredIntegers)
            return newInt(bounds.lower, bounds.upper)
        }
        internal fun newInt(lo: Long?, hi: Long?): Int {
            intDomains.add(openOrFinite(lo, hi))
            return nextInt++
        }

        /** The [i]-th item of this command as an atom's text, or a clean [UnsupportedSmtException]
         *  naming [what] when the argument is absent or not an atom (instead of a raw cast/index crash). */
        internal fun SExpr.SList.atomAt(i: Int, what: String): String =
            (items.getOrNull(i) as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("malformed $what")

        /** The [i]-th item of this command, or a clean [UnsupportedSmtException] naming [what] when absent. */
        internal fun SExpr.SList.argAt(i: Int, what: String): SExpr =
            items.getOrNull(i) ?: throw UnsupportedSmtException("malformed $what")

        internal fun SExpr.SList.requireSize(size: Int, what: String) {
            if (items.size != size) {
                throw UnsupportedSmtException("$what expects ${size - 1} argument${if (size == 2) "" else "s"}")
            }
        }

        fun command(e: SExpr) {
            val command = e as? SExpr.SList ?: throw UnsupportedSmtException("expected a parenthesized command")
            val head = command.atomAt(0, "command")
            when (head) {
                "declare-const" -> {
                    command.requireSize(3, "declare-const")
                    declare(command.atomAt(1, "declare-const name"), command.atomAt(2, "declare-const sort"))
                }

                "declare-fun" -> declareFun(command)

                "define-fun" -> defineFun(command)

                "assert" -> {
                    command.requireSize(2, "assert")
                    asserts.add(command.argAt(1, "assert"))
                }

                "minimize" -> {
                    command.requireSize(2, "minimize")
                    objectiveSpec = command.argAt(1, "minimize") to false
                }

                "maximize" -> {
                    command.requireSize(2, "maximize")
                    objectiveSpec = command.argAt(1, "maximize") to true
                }

                // These commands do not change the asserted model. Keep accepting common solver-driver
                // commands, but reject every other head: silently ignoring a misspelled assertion command
                // constructs a different problem from the source script.
                "set-logic", "set-info", "set-option", "check-sat", "get-model", "get-objectives", "exit" -> Unit

                else -> throw UnsupportedSmtException("unsupported command '$head'")
            }
        }

        /** Declare `(declare-fun name (argSorts…) sort)`. Only 0-arity functions (constants) are
         *  supported; a non-empty argument list is a genuine function symbol, which QF_LIA-as-solved
         *  here cannot treat as a variable, so reject it rather than silently declaring a constant. */
        private fun declareFun(e: SExpr.SList) {
            e.requireSize(4, "declare-fun")
            val name = e.atomAt(1, "declare-fun name")
            val argSorts = e.argAt(2, "declare-fun '$name' argument sorts")
            if (argSorts !is SExpr.SList || argSorts.items.isNotEmpty()) {
                throw UnsupportedSmtException(
                    "declare-fun '$name' with arguments (only 0-arity constants are supported)",
                )
            }
            declare(name, e.atomAt(3, "declare-fun '$name' sort"))
        }

        // Record a non-recursive `(define-fun name ((p T)…) retSort body)` as an inlinable macro.
        private fun defineFun(e: SExpr.SList) {
            e.requireSize(5, "define-fun")
            val name = e.atomAt(1, "define-fun name")
            val paramList = e.items.getOrNull(2) as? SExpr.SList
                ?: smtUnsupported("define-fun '$name': bad params")
            val parameterSorts = ArrayList<Sort>(paramList.items.size)
            val params = paramList.items.map { p ->
                val param = p as? SExpr.SList
                    ?: smtUnsupported("define-fun '$name': bad parameter")
                param.requireSize(2, "define-fun '$name' parameter")
                val paramName = param.atomAt(0, "define-fun '$name' parameter")
                val parameterSort = param.atomAt(1, "define-fun '$name' parameter sort")
                requireSort(parameterSort, "define-fun '$name' parameter '$paramName'")
                parameterSorts.add(when (parameterSort) {
                    "Bool" -> Sort.BOOL
                    "Real" -> Sort.REAL
                    else -> Sort.INT
                })
                paramName
            }
            val retSort = e.atomAt(3, "define-fun '$name' return sort")
            requireSort(retSort, "define-fun '$name'")
            if (params.size != params.toSet().size) {
                smtUnsupported("define-fun '$name' has duplicate parameter names")
            }
            requireFreshName(name)
            macros[name] = Macro(
                params,
                parameterSorts,
                e.argAt(4, "define-fun '$name' body"),
                isBool = retSort == "Bool",
                isReal = retSort == "Real",
            )
        }

        private fun declare(name: String, sort: String) {
            requireFreshName(name)
            when (sort) {
                "Int" -> intNames[name] = newInt()
                "Bool" -> boolNames[name] = newBool()
                "Real" -> realNames[name] = nextReal++
                else -> throw UnsupportedSmtException("unsupported sort '$sort' for '$name'")
            }
        }

        private fun requireFreshName(name: String) {
            if (name in boolNames || name in intNames || name in realNames || name in macros) {
                throw UnsupportedSmtException("duplicate declaration of '$name'")
            }
        }

        private fun requireSort(sort: String, what: String) {
            if (sort != "Int" && sort != "Bool" && sort != "Real") {
                throw UnsupportedSmtException("unsupported sort '$sort' for $what")
            }
        }

        fun build(): SmtLibProblem = build(includeObjective = true)

        internal fun buildSource(): SmtLibProblem = build(includeObjective = false)

        private fun build(includeObjective: Boolean): SmtLibProblem {
            affineRealTables.declarations(boolNames.values)
            inferBounds()
            retainDeclarationBounds()
            if (hasOpenProductOperand()) closeProductBounds()
            for (a in asserts) assert(a)
            lowerOpenIteChains()
            val compiledObjective = if (includeObjective) objectiveSpec?.let { (t, _) ->
                if (isRealExpr(t)) realObjective(t, false) else linearObjective(t, false)
            } else null
            lowerOpenIteChains() // an objective term can open chains of its own
            conditionalEqualities.expandPending(this)
            val removed = conditionalEqualities.retainNeededDefinitions(
                factors, intNames.values, boolNames.values, compiledObjective,
            )
            val objective = compactConditionalColumns(removed, compiledObjective)
            factors.addAll(affineRealTables.infer())
            return sourceProblem(objective, factors.toTypedArray())
        }

        internal fun refutedSource(premises: Problem): SmtLibProblem = sourceProblem(null,
            // Open-bound routing reads rows with source coordinates.
            premises.factors + ExactLinear.of(intArrayOf(), emptyList(), LinearOp.EQ, BigFraction.ONE))

        private fun sourceProblem(objective: LinearObjectiveSpec?, sourceFactors: Array<Factor>): SmtLibProblem {
            val model = Problem(
                settings = settings,
                semantics = semantics,
                numBoolVars = nextBool,
                intBounds = modelIntBounds(),
                factors = sourceFactors,
                numRealVars = nextReal,
                realLower = DoubleArray(nextReal) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(nextReal) { Double.POSITIVE_INFINITY },
            )
            return SmtLibProblem(
                model,
                objective = objective,
                intVarNames = LinkedHashMap(intNames),
                boolVarNames = LinkedHashMap(boolNames),
                realVarNames = LinkedHashMap(realNames),
                sense = if (objectiveSpec?.second == true) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
            )
        }

        private fun compactConditionalColumns(
            removed: SmtLibConditionalEquality.UnusedColumns,
            objective: LinearObjectiveSpec?,
        ): LinearObjectiveSpec? {
            if (removed.ints.isEmpty() && removed.bools.isEmpty()) return objective
            val mapping = IntArray(nextInt) { -1 }
            val retained = ArrayList<PresolveDomain>()
            for (variable in mapping.indices) {
                if (variable !in removed.ints) {
                    mapping[variable] = retained.size
                    retained.add(intDomains[variable])
                }
            }
            var retainedBooleans = 0
            val boolMapping = IntArray(nextBool) { variable ->
                if (variable in removed.bools) -1 else retainedBooleans++
            }
            val remap = VarRemap(boolMapping, mapping)
            for (index in factors.indices) {
                val factor = factors[index]
                if (factor.variables.ints.isNotEmpty() || factor.variables.boolVars.isNotEmpty()) {
                    check(factor.variables.ints.all { mapping[it] >= 0 })
                    check(factor.variables.boolVars.all { boolMapping[it] >= 0 })
                    factors[index] = factor.remap(remap)
                }
            }
            for (entry in intNames.entries) entry.setValue(mapping[entry.value])
            for (entry in boolNames.entries) entry.setValue(boolMapping[entry.value])
            intDomains.clear()
            intDomains.addAll(retained)
            nextInt = retained.size
            nextBool = retainedBooleans
            return objective?.copy(
                intCoefficients = compactCoefficients(objective.intCoefficientValues, mapping, nextInt),
                boolWeights = compactCoefficients(objective.boolWeightValues, boolMapping, nextBool),
            )
        }

        private fun compactCoefficients(coefficients: LongArray, mapping: IntArray, size: Int): LongArray {
            if (coefficients.isEmpty()) return coefficients
            val compacted = LongArray(size)
            for (variable in coefficients.indices) {
                val target = mapping[variable]
                if (target >= 0) compacted[target] = coefficients[variable]
            }
            return compacted
        }
    }

    /** A non-recursive `define-fun` macro: its [params], [body] term, and its return sort. [isReal] is
     *  what lets the syntactic real classifier see through a macro reference: a `Real`-returning macro
     *  used in a relation is a real relation even when nothing else in the relation is syntactically
     *  real, so its body is inlined in a real — not integer — context. */
    internal class Macro(
        val params: List<String>,
        val parameterSorts: List<Sort>,
        val body: SExpr,
        val isBool: Boolean,
        val isReal: Boolean,
    )
}
