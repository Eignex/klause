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

[![License](https://img.shields.io/github/license/eignex/klause)](LICENSE)

Klause is a constraint solver written in Kotlin Multiplatform. It solves satisfaction and optimization problems over Booleans, integers and reals: SAT and MaxSAT, constraint programming, mixed integer linear programming, and SMT arithmetic. Use it as a library through the Kotlin DSL, or run the CLI on the JVM or as a native executable.

## What it is for

Klause is built for applications that ask many questions of the same model, not only for one-shot solving. A model is compiled once and then queried repeatedly inside a Kotlin or JVM process:

- Solve again under different assumptions, keeping learned and heuristic state between queries.
- Enumerate solutions without repetition, or draw diverse samples across the feasible region.
- Count solutions exactly or approximately, with proven lower and upper bounds.
- Check a proposed assignment against the original model.

Typical uses are constraint-aware test and fuzz input generation, configuration synthesis, scheduling and assignment, and long-running services that re-solve and re-sample one model.

Complete search, local search, LP relaxations and arithmetic theories all work on that same model, alone or together in a portfolio, and every result they publish is checked against the original model before it is returned.

It reads FlatZinc, XCSP3, SMT-LIB, MPS, DIMACS, OPB/WBO and WCNF, and MiniZinc models through the included solver integration.

## How it differs

Specialised solvers such as CP-SAT, Z3 and Gurobi are mature and usually faster on problems squarely in their own domain. Klause trades some of that speed for breadth and checked results.

- **Compared with CP-SAT**, which works over Booleans and integers, Klause also handles real variables, unbounded integers and nonlinear arithmetic in the same model, and can run local search on the same models.
- **Compared with Z3**, which decides logical theories, Klause adds optimization, finite-domain global constraints, local search and MIP-style LP relaxations, while still deciding linear integer and real arithmetic exactly.
- **Compared with MIP solvers such as Gurobi or HiGHS**, which solve LP and MIP in floating point, Klause certifies LP results in exact rational arithmetic before using them as proofs, and combines MIP with propagation and local search.
- **Compared with all of these**, Klause is written in Kotlin and bundles no native solver library, so it runs wherever Kotlin Multiplatform does.

## The LP engine

- Klause has its own revised simplex written in Kotlin: a floating-point dual simplex with Devex pricing, sparse LU factorization and Forrest-Tomlin updates.
- The LP lives inside search. Bound changes and backtracking update the same warm basis instead of starting a new solve at every node.
- Every LP result is checked in exact rational arithmetic before it counts, whether it is a feasible point, an objective bound, an infeasibility proof or an unbounded ray. A result that fails the check is never used as a proof.
- LP bounds and conflicts are explained by the source constraints, so they feed conflict learning the same way propagation does.
- Strict inequalities are handled exactly, as SMT linear arithmetic requires.

Compared with the others:

- **Gurobi and HiGHS** solve in floating point and accept results within tolerances. Klause also solves in floating point for speed, but publishes only results it has certified exactly.
- **Z3** runs its simplex in exact rational arithmetic throughout. Klause does the heavy work in floating point and spends exact arithmetic only on certification.
- **CP-SAT** also runs an LP inside its search, but over integer variables only. Klause's LP carries continuous columns too, so the same engine serves MIP, CP relaxations and real arithmetic.

## Limitations

- Klause is young. On problems squarely in another solver's domain, such as large MIPs, hard SAT instances or SMT benchmarks, the established solvers are usually much faster.
- Nonlinear integer and transcendental arithmetic are incomplete: Klause can find and check solutions, but may stop with an unknown result instead of a proof. Nonconvex quadratic objectives get no global optimality claim when a branch stays unresolved.
- SMT-LIB support covers quantifier-free arithmetic only. Bit-vectors, arrays, strings, quantifiers, uninterpreted functions and incremental solving are rejected.
- Proofs are checked in memory, but Klause cannot yet export proof files for an independent checker.
- Not every kind of model has every search style yet; local search and portfolio coverage still have gaps.

## Documentation

User documentation is at [eignex.com/docs/klause](https://eignex.com/docs/klause/). The internal developer documentation is a book; start at its [table of contents](docs/README.md).

## Build

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

The JVM launcher requires JDK 25 or newer.
