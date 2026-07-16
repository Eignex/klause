package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.config.DEFAULT_UNBOUNDED_SEARCH_BOUND
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective

/** Raised when an SMT-LIB construct outside the supported QF_LIA subset is encountered. */
class UnsupportedSmtException(msg: String) : FormatException("SMT-LIB QF_LIA", msg)

/** One lowered linear relation `Σ coeffs·vars ⟨op⟩ bound` (shared by bound inference and lowering). */
internal data class Rel(val vars: IntArray, val coeffs: LongArray, val op: LinearOp, val bound: Long)

/** A parsed SMT-LIB instance lifted into klause's representation. */
data class SmtLibProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** Declared `Int` variable name to int id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Bool` variable name to bool id. */
    val boolVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** True when some integer variable's true (infinite or wider) domain was narrowed to the finite
     *  solver range because no tight bound was provable. An `unsat` over such a clamped model is only
     *  `unsat` within the finite range — the honest verdict for the original problem is `unknown`. */
    val domainsClamped: Boolean = false,
)

/** Parser/compiler for the supported SMT-LIB QF_LIA subset. The [Builder]'s per-concern compilation
 *  steps live in sibling files as extension functions: bound inference in `SmtLibBounds.kt`, boolean /
 *  assert / distinct compilation in `SmtLibExpr.kt`, and linear-term / relation / objective lowering in
 *  `SmtLibLinear.kt`. */
object SmtLibQfLia {
    /** Parse SMT-LIB QF_LIA [text] into an [SmtLibProblem]. A variable with no provable bound (and a
     *  derived bound past the range) falls back to / is clamped into `[unboundedIntLo, unboundedIntHi]`
     *  — the same default int range as the FlatZinc front-end ([com.eignex.klause.config.KlauseConfig]). */
    fun parse(
        text: String,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        strictBounds: Boolean = false,
        searchBound: Long = DEFAULT_UNBOUNDED_SEARCH_BOUND,
    ): SmtLibProblem {
        val b = Builder(unboundedIntLo, unboundedIntHi, strictBounds, searchBound)
        for (cmd in SExprReader(text).readAll()) b.command(cmd)
        return b.build()
    }

    /** Mutable compilation state for one SMT-LIB parse. The heavy compilation logic is attached as
     *  `internal fun SmtLibQfLia.Builder.…` extension functions in the sibling `SmtLib*.kt` files. */
    internal class Builder(
        val unboundedIntLo: Long,
        val unboundedIntHi: Long,
        val strictBounds: Boolean,
        val searchBound: Long = DEFAULT_UNBOUNDED_SEARCH_BOUND,
    ) : CnfLowering {
        internal val boolNames = HashMap<String, Int>()
        internal val intNames = HashMap<String, Int>()
        internal var nextBool = 0
        internal var nextInt = 0
        internal val intDomains = ArrayList<PresolveDomain>()
        override val factors = ArrayList<Factor>()
        internal val asserts = ArrayList<SExpr>()
        private var objectiveSpec: Pair<SExpr, Boolean>? = null // (term, negate)
        override var trueLitCache: Int = -1

        /** Set once bound inference has to clamp an integer variable to the finite solver range; makes
         *  an eventual `unsat` verdict `unknown` (see [SmtLibProblem.domainsClamped]). */
        internal var domainsClamped = false

        /** Non-recursive `define-fun` macros: name to (parameter names, body term, Bool-return flag).
         *  A call `(f a…)` is inlined by binding the parameters to the arguments like a `let`. */
        internal val macros = HashMap<String, Macro>()

        internal class Binding(val isBool: Boolean) {
            var lin: LinComb? = null
            var lit: Int? = null
        }

        // Let scoping as a heap-allocated stack, not recursion. `bindingStacks` maps each name to its
        // shadow stack (innermost binding on top) for O(1) lookup; `scopeNames` records the names bound
        // by each active scope so it can be popped. A deeply nested formula unwinds its `let` chain
        // iteratively into these structures instead of recursing through the call stack.
        private val bindingStacks = HashMap<String, ArrayDeque<Binding>>()
        private val scopeNames = ArrayDeque<List<String>>()
        internal fun lookup(name: String): Binding? = bindingStacks[name]?.lastOrNull()

        /** Compile one `let`'s bindings (in parallel — their values don't see each other) and push them
         *  as a new scope. */
        internal fun pushLetScope(bindingList: SExpr) {
            require(bindingList is SExpr.SList) { "malformed let bindings" }
            pushScopeBindings(
                bindingList.items.map { pair ->
                    val p = pair as? SExpr.SList ?: throw UnsupportedSmtException("malformed let binding")
                    val name = (p.items[0] as SExpr.Atom).text
                    val expr = p.items[1]
                    val b = Binding(isBool = isBoolExpr(expr))
                    if (b.isBool) b.lit = compileBool(expr) else b.lin = linearTerm(expr)
                    name to b
                },
            )
        }

        /** Push already-compiled [bound] bindings as one new innermost scope. Used by the iterative
         *  term evaluator, which folds a `let`'s binding values in-stack before building the scope. */
        internal fun pushScopeBindings(bound: List<Pair<String, Binding>>) {
            val names = ArrayList<String>(bound.size)
            for ((name, b) in bound) {
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
            // marker; bound inference then closes it (or leaves it open for OBBT).
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
        private fun SExpr.SList.atomAt(i: Int, what: String): String =
            (items.getOrNull(i) as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("malformed $what")

        /** The [i]-th item of this command, or a clean [UnsupportedSmtException] naming [what] when absent. */
        private fun SExpr.SList.argAt(i: Int, what: String): SExpr =
            items.getOrNull(i) ?: throw UnsupportedSmtException("malformed $what")

        fun command(e: SExpr) {
            if (e !is SExpr.SList || e.items.isEmpty()) return
            val head = (e.items[0] as? SExpr.Atom)?.text ?: return
            when (head) {
                "declare-const" -> declare(e.atomAt(1, "declare-const name"), e.atomAt(2, "declare-const sort"))
                "declare-fun" -> declareFun(e)
                "define-fun" -> defineFun(e)
                "assert" -> asserts.add(e.argAt(1, "assert"))
                "minimize" -> objectiveSpec = e.argAt(1, "minimize") to false
                "maximize" -> objectiveSpec = e.argAt(1, "maximize") to true
                else -> Unit // set-logic / set-info / check-sat / get-* / exit — ignored
            }
        }

        /** Declare `(declare-fun name (argSorts…) sort)`. Only 0-arity functions (constants) are
         *  supported; a non-empty argument list is a genuine function symbol, which QF_LIA-as-solved
         *  here cannot treat as a variable, so reject it rather than silently declaring a constant. */
        private fun declareFun(e: SExpr.SList) {
            val name = e.atomAt(1, "declare-fun name")
            val argSorts = e.argAt(2, "declare-fun '$name' argument sorts")
            if (argSorts !is SExpr.SList || argSorts.items.isNotEmpty()) {
                throw UnsupportedSmtException(
                    "declare-fun '$name' with arguments (only 0-arity constants are supported)",
                )
            }
            declare(name, e.atomAt(3, "declare-fun '$name' sort"))
        }

        /** Record a non-recursive `(define-fun name ((p T)…) retSort body)` as an inlinable macro. */
        private fun defineFun(e: SExpr.SList) {
            val name = e.atomAt(1, "define-fun name")
            val paramList = e.items.getOrNull(2) as? SExpr.SList
                ?: throw UnsupportedSmtException("define-fun '$name': bad params")
            val params = paramList.items.map { p ->
                ((p as? SExpr.SList)?.items?.getOrNull(0) as? SExpr.Atom)?.text
                    ?: throw UnsupportedSmtException("define-fun '$name': bad parameter")
            }
            val retSort = e.atomAt(3, "define-fun '$name' return sort")
            macros[name] = Macro(params, e.argAt(4, "define-fun '$name' body"), isBool = retSort == "Bool")
        }

        private fun declare(name: String, sort: String) {
            when (sort) {
                "Int" -> intNames[name] = newInt()
                "Bool" -> boolNames[name] = newBool()
                "Real" -> throw UnsupportedSmtException("Real sort for '$name' (QF_LIA is integer-only)")
                else -> throw UnsupportedSmtException("unsupported sort '$sort' for '$name'")
            }
        }

        fun build(): SmtLibProblem {
            inferBounds()
            for (a in asserts) assert(a)
            boundUnboundedVars()
            val objective = objectiveSpec?.let { (t, neg) -> linearObjective(t, neg) }
            // The single search seam: every domain must be Finite by now (boundUnboundedVars closes
            // every Open one). An Open here would be a bug, but the sealed type kept it from flowing
            // anywhere a searchable IntDomain was expected, so this cast is the only place it can surface.
            val domains = Array(intDomains.size) { i ->
                (intDomains[i] as? PresolveDomain.Finite)?.domain ?: error("open domain reached search")
            }
            return SmtLibProblem(
                Problem(
                    numBoolVars = nextBool,
                    numIntVars = nextInt,
                    intDomains = domains,
                    factors = factors.toTypedArray(),
                    // Defer the root bake: on a wide clamped domain an integer-infeasible equality (e.g.
                    // a divisibility contradiction) would grind O(span) at construction. Presolve's
                    // strengthen pass now catches that infeasibility first, at solve time, before the
                    // (now-lazy) bake runs.
                    preFolded = true,
                ),
                objective,
                intVarNames = LinkedHashMap(intNames),
                boolVarNames = LinkedHashMap(boolNames),
                sense = if (objectiveSpec?.second == true) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
                domainsClamped = domainsClamped,
            )
        }
    }

    /** A non-recursive `define-fun` macro: its [params], [body] term, and whether it returns `Bool`. */
    internal class Macro(val params: List<String>, val body: SExpr, val isBool: Boolean)
}
