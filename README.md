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

## The LP engine

- Klause has its own revised simplex written in Kotlin: a floating-point dual simplex with Devex pricing, sparse LU factorization and Forrest-Tomlin updates.
- The LP lives inside search. Bound changes and backtracking update the same warm basis instead of starting a new solve at every node.
- Every LP result is checked in exact rational arithmetic before it counts, whether it is a feasible point, an objective bound, an infeasibility proof or an unbounded ray.
- LP bounds and conflicts are explained by the source constraints, so they feed conflict learning the same way propagation does.
- Exact mode extends this to floating-point formats. FlatZinc floats are solved by exact theory search instead of a grid, MPS models are solved with zero tolerance, and every reported point satisfies the source constraints exactly.

## Compared with other solvers

Specialised solvers are mature and usually faster on problems squarely in their own domain. Klause trades some of that speed for breadth and checked results, and bundles no native solver library.

- CP-SAT works over Booleans and integers; Klause also handles reals, unbounded integers and nonlinear arithmetic in the same model.
- Z3 decides logical theories; Klause adds optimization, global constraints and local search while still deciding linear integer and real arithmetic exactly.
- Gurobi and HiGHS accept floating-point results within tolerances; Klause solves in floating point too but publishes only results it has certified exactly.

## Limitations

- On large MIPs, hard SAT instances or SMT benchmarks, established solvers are usually much faster.
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
