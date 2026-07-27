<p align="center">
  <a href="https://eignex.com/">
    <picture>
      <source media="(prefers-color-scheme: dark)"
        srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner-white.svg">
      <source media="(prefers-color-scheme: light)"
        srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg">
      <img alt="Eignex"
        src="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg"
        style="max-width: 100%; width: 22em;">
    </picture>
  </a>
</p>

# Klause

[![License](https://img.shields.io/github/license/eignex/klause)](https://github.com/eignex/klause/blob/main/LICENSE)

Klause is a hybrid solver for booleans, finite domain integers and linear
reals.

It features a modular CDCL backtrack solver with:
 - Constraint programming propagators (alldifferent, circuit, and the usual
   suspects).
 - A boolean-only fast path for SAT and pseudo-Boolean problems.
 - A linear programming solver for relaxations and continuous variables.

Klause can read and solve problems in a wide range of input formats:
MiniZinc, XCSP3, SMT-LIB (QF_LIRA), MPS, OPB, and DIMACS. The CLI is
available both as a native binary and on JVM through KMP (kotlin
multi-platform).

You can also use it as a kotlin/java library with a nice DSL. There are
wrappers to encode non-linear floats and sets over ints or nominal labels
too. Any variable can be declared optional, with constraints lowered to
reified or aggregation-aware forms automatically.

## Installation

TODO

## Engines

Besides backtrack there's also a highly customizable local search and large
neighbourhood search engine. The local search engine generalizes
meta-heuristics like ProbSAT, CBLS, feasibility jump, DDFW, simulated
annealing etc.

All engines are combined and selected automatically in the portfolio based
on whether there is an optimization target or just satisfiability and what
types of variables and constraints the problem contain.

## Theories

Linear reals are handled by the LP: continuous variables exist only as LP
columns and are never branched on. The LP runs in double precision but
every bound and infeasibility verdict is certified exactly, so floating
point never decides an answer. Non-linear float constraints are instead
discretized into integer buckets at a configurable resolution.

The SMT-LIB frontend decides QF_LIRA, quantifier-free linear integer and
real arithmetic, optionally with a minimize or maximize objective.

## Use cases

Klause targets problems where the constraint system is the model and the
question is "give me some valid configurations" rather than "prove this
assertion holds". It is built both for high performance one-shot solving
and for long-running services that re-solve and re-sample the same model.

- Test and fuzz input generation. Draw diverse valid inputs spread across
  the feasible region instead of getting rejected on validation.
- Configuration synthesis. Find feature flags, resource caps or routing
  weights that satisfy the rules, optionally ranked by an objective.
- Parameter optimization under constraints.
  [Combo](https://github.com/Eignex/combo) runs bandit and Bayesian
  optimization on top of klause.
- Scheduling and assignment. Tasks to machines, students to rooms,
  campaigns to budgets.

Sampling and counting are first-class and run natively over the compiled
problem, without translation to CNF:

- Drawing samples with replacement.
- Enumerating solutions without replacement.
- Exact and approximate model counting.
- Near-uniform sampling.

Klause is not a full SMT solver (no bitvectors, arrays, strings, or
quantifiers) and not a verification framework. For those, use Z3, CVC5 or a
proof assistant.

## Example

Declare a schema, compile it, and enumerate solutions:

```kotlin
class Order : VariableSchema() {
    val a by intVar(min = 1, max = 3)
    val b by intVar(min = 1, max = 3)
    val c by intVar(min = 1, max = 3)
    val unique by constraint { allDifferent(a, b, c) }
}

val schema = Order()
val compiled = schema.compile()
for (sample in BacktrackSolver(compiled).enumerate().take(5)) {
    println(compiled.decode(schema.a, sample))
}
```
