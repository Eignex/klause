package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.formats.FormatException
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.CnfLowering
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** Raised when an SMT-LIB construct outside the supported linear-arithmetic subset is encountered. */
class UnsupportedSmtException(msg: String) : FormatException("SMT-LIB", msg)

/** Reject an unsupported construct with a clean [UnsupportedSmtException]. */
internal fun smtUnsupported(msg: String): Nothing = throw UnsupportedSmtException(msg)

/** One lowered linear relation `Σ coeffs·vars ⟨op⟩ bound` (shared by bound inference and lowering). */
internal data class Rel(val vars: IntArray, val coeffs: LongArray, val op: LinearOp, val bound: Long)

/** A parsed SMT-LIB instance lifted into klause's representation. */
data class SmtLibProblem(
    /** Compiled model. Open integer sides remain open until a finite-search backend materializes them. */
    val model: ProblemSpec,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** Declared `Int` variable name to int id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Bool` variable name to bool id. */
    val boolVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Real` variable name to LP-only real id. */
    val realVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
)

/** Parser/compiler for the supported SMT-LIB linear-arithmetic subset (QF_LIA / QF_LRA / QF_LIRA
 *  fragments). The [Builder]'s per-concern compilation steps live in sibling files as extension
 *  functions: bound inference in `SmtLibBounds.kt`, boolean / assert / distinct compilation in
 *  `SmtLibExpr.kt`, linear-term / relation / objective lowering in `SmtLibLinear.kt`, and the real
 *  (LRA) lowering in `SmtLibReal.kt`. */
object SmtLib {
    /** Parse SMT-LIB linear-arithmetic [text] into an [SmtLibProblem]. Source integer sides without a
     *  provable bound remain open in its [ProblemSpec]. */
    fun parse(
        text: String,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        strictBounds: Boolean = false,
    ): SmtLibProblem = parse(StringCharSource(text), unboundedIntLo, unboundedIntHi, strictBounds)

    /** Parse SMT-LIB linear-arithmetic from a streamed [source], pulling one top-level command at a time
     *  so the whole script is never materialized. Semantically identical to the [String] overload. */
    fun parse(
        source: CharSource,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        strictBounds: Boolean = false,
    ): SmtLibProblem {
        val b = Builder(unboundedIntLo, unboundedIntHi, strictBounds)
        val reader = SExprReader(source)
        while (true) b.command(reader.readCommandOrNull() ?: break)
        return b.build()
    }

    /** Mutable compilation state for one SMT-LIB parse. The heavy compilation logic is attached as
     *  `internal fun SmtLib.Builder.…` extension functions in the sibling `SmtLib*.kt` files. */
    internal class Builder(val unboundedIntLo: Long, val unboundedIntHi: Long, val strictBounds: Boolean) :
        CnfLowering {
        internal val boolNames = HashMap<String, Int>()
        internal val intNames = HashMap<String, Int>()
        internal val realNames = HashMap<String, Int>()
        internal var nextBool = 0
        internal var nextInt = 0
        internal var nextReal = 0

        /** Chain variables of the wide-coefficient row encoding, keyed `(varId shl 3) or (k shl 1) or isInt`:
         *  chain k stands for `B^k · var` (see [wideRealRow]). */
        internal val realChainVars = HashMap<Long, Int>()

        /** The auxiliary real pinned to 1 that absorbs a wide row's oversized constant, or -1. */
        internal var realOneVar = -1
        internal val intDomains = ArrayList<PresolveDomain>()

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

            /**
             * A Bool binding's source term while it is still uncompiled.
             *
             * Compiling it on sight costs the reified form even where the value is only ever forced true:
             * a `distinct` over n operands reifies to n(n-1)/2 auxiliary literals and as many reified
             * rows, where asserting the same term posts plain disequalities and allocates nothing. So the
             * term is kept until something asks for its literal, which lets [assert] recognise a binding
             * it can post directly.
             */
            var srcBool: SExpr? = null
        }

        // Let scoping as a heap-allocated stack, not recursion. `bindingStacks` maps each name to its
        // shadow stack (innermost binding on top) for O(1) lookup; `scopeNames` records the names bound
        // by each active scope so it can be popped. A deeply nested formula unwinds its `let` chain
        // iteratively into these structures instead of recursing through the call stack.
        private val bindingStacks = HashMap<String, ArrayDeque<Binding>>()
        private val scopeNames = ArrayDeque<List<String>>()
        internal fun lookup(name: String): Binding? = bindingStacks[name]?.lastOrNull()

        /** The literal for a Bool binding, compiling its held term on the first ask. */
        internal fun boolLit(b: Binding): Int {
            b.lit?.let { return it }
            val src = b.srcBool ?: throw UnsupportedSmtException("Bool binding has no value")
            return compileBool(src).also {
                b.lit = it
                b.srcBool = null
            }
        }

        /** Compile one `let`'s bindings (in parallel — their values don't see each other) and push them
         *  as a new scope. */
        internal fun pushLetScope(bindingList: SExpr) {
            val list = bindingList as? SExpr.SList ?: throw UnsupportedSmtException("malformed let bindings")
            pushScopeBindings(
                list.items.map { pair ->
                    val p = pair as? SExpr.SList ?: throw UnsupportedSmtException("malformed let binding")
                    val name = p.atomAt(0, "let binding name")
                    val expr = p.argAt(1, "let binding value")
                    val b = Binding(isBool = isBoolExpr(expr), isReal = !isBoolExpr(expr) && isRealExpr(expr))
                    when {
                        b.isBool -> b.srcBool = expr
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
            for ((name, b) in bound) {
                // A value reachable by name can be referenced any number of times, so an `ite` chain it
                // holds is finished: nothing can extend it any more, and it must define its variable.
                (b.lin as? IntComb.Narrow)?.lin?.asSimpleVar()?.let { closeIteChain(it) }
                bindingStacks.getOrPut(name) { ArrayDeque() }.addLast(b)
                names.add(name)
            }
            scopeNames.addLast(names)
        }

        internal fun popLetScope() {
            for (name in scopeNames.removeLast()) {
                val stack = bindingStacks.getValue(name)
                stack.removeLast()
                if (stack.isEmpty()) bindingStacks.remove(name)
            }
        }

        override fun newBool(): Int = nextBool++
        internal fun newInt(): Int {
            // An unbounded declaration is Open on whichever side the caller left at the `Long.MIN/MAX`
            // marker; source bound inference either closes it or preserves the open side.
            intDomains.add(
                openOrFinite(
                    if (unboundedIntLo == Long.MIN_VALUE) null else unboundedIntLo,
                    if (unboundedIntHi == Long.MAX_VALUE) null else unboundedIntHi,
                ),
            )
            return nextInt++
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
            val params = paramList.items.map { p ->
                val param = p as? SExpr.SList
                    ?: smtUnsupported("define-fun '$name': bad parameter")
                param.requireSize(2, "define-fun '$name' parameter")
                val paramName = param.atomAt(0, "define-fun '$name' parameter")
                requireSort(
                    param.atomAt(1, "define-fun '$name' parameter sort"),
                    "define-fun '$name' parameter '$paramName'",
                )
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

        fun build(): SmtLibProblem {
            inferBounds()
            for (a in asserts) assert(a)
            lowerOpenIteChains()
            val objective = objectiveSpec?.let { (t, neg) ->
                if (isRealExpr(t)) realObjective(t, neg) else linearObjective(t, neg)
            }
            lowerOpenIteChains() // an objective term can open chains of its own
            val sourceBounds = modelIntBounds()

            val model = ProblemSpec(
                numBoolVars = nextBool,
                intBounds = sourceBounds,
                factors = factors.toTypedArray(),
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
    }

    /** A non-recursive `define-fun` macro: its [params], [body] term, and its return sort. [isReal] is
     *  what lets the syntactic real classifier see through a macro reference: a `Real`-returning macro
     *  used in a relation is a real relation even when nothing else in the relation is syntactically
     *  real, so its body is inlined in a real — not integer — context. */
    internal class Macro(val params: List<String>, val body: SExpr, val isBool: Boolean, val isReal: Boolean)
}
