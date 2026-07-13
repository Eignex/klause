package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.LinComb
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective

/** Raised when an SMT-LIB construct outside the supported QF_LIA subset is encountered. */
class UnsupportedSmtException(msg: String) : RuntimeException("klause SMT-LIB QF_LIA: $msg")

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
    /** True when the parsed objective was a maximize directive. */
    val maximize: Boolean = false,
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
    ): SmtLibProblem {
        val b = Builder(unboundedIntLo, unboundedIntHi, strictBounds)
        for (cmd in SExprReader(text).readAll()) b.command(cmd)
        return b.build()
    }

    /** Mutable compilation state for one SMT-LIB parse. The heavy compilation logic is attached as
     *  `internal fun SmtLibQfLia.Builder.…` extension functions in the sibling `SmtLib*.kt` files. */
    internal class Builder(val unboundedIntLo: Long, val unboundedIntHi: Long, val strictBounds: Boolean) :
        CnfLowering {
        internal val boolNames = HashMap<String, Int>()
        internal val intNames = HashMap<String, Int>()
        internal var nextBool = 0
        internal var nextInt = 0
        internal val intDomains = ArrayList<IntDomain>()
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
            intDomains.add(IntDomain(unboundedIntLo, unboundedIntHi))
            return nextInt++
        }
        internal fun newInt(lo: Long, hi: Long): Int {
            intDomains.add(IntDomain(lo, hi))
            return nextInt++
        }

        fun command(e: SExpr) {
            if (e !is SExpr.SList || e.items.isEmpty()) return
            val head = (e.items[0] as? SExpr.Atom)?.text ?: return
            when (head) {
                "declare-const" -> declare((e.items[1] as SExpr.Atom).text, (e.items[2] as SExpr.Atom).text)
                "declare-fun" -> declare((e.items[1] as SExpr.Atom).text, (e.items[3] as SExpr.Atom).text)
                "define-fun" -> defineFun(e)
                "assert" -> asserts.add(e.items[1])
                "minimize" -> objectiveSpec = e.items[1] to false
                "maximize" -> objectiveSpec = e.items[1] to true
                else -> Unit // set-logic / set-info / check-sat / get-* / exit — ignored
            }
        }

        /** Record a non-recursive `(define-fun name ((p T)…) retSort body)` as an inlinable macro. */
        private fun defineFun(e: SExpr.SList) {
            val name = (e.items[1] as SExpr.Atom).text
            val paramList = e.items[2] as? SExpr.SList ?: throw UnsupportedSmtException(
                "define-fun '$name': bad params",
            )
            val params = paramList.items.map { p ->
                ((p as? SExpr.SList)?.items?.getOrNull(0) as? SExpr.Atom)?.text
                    ?: throw UnsupportedSmtException("define-fun '$name': bad parameter")
            }
            val retSort = (e.items[3] as SExpr.Atom).text
            macros[name] = Macro(params, e.items[4], isBool = retSort == "Bool")
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
            return SmtLibProblem(
                Problem(
                    numBoolVars = nextBool,
                    numIntVars = nextInt,
                    intDomains = intDomains.toTypedArray(),
                    factors = factors.toTypedArray(),
                ),
                objective,
                intVarNames = LinkedHashMap(intNames),
                boolVarNames = LinkedHashMap(boolNames),
                maximize = objectiveSpec?.second ?: false,
                domainsClamped = domainsClamped,
            )
        }
    }

    /** A non-recursive `define-fun` macro: its [params], [body] term, and whether it returns `Bool`. */
    internal class Macro(val params: List<String>, val body: SExpr, val isBool: Boolean)
}
