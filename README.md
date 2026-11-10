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

Complete search, local search, LP relaxations and arithmetic theories all work on that same model, alone or together in a portfolio. What backs each kind of result is set out under [What results guarantee](#what-results-guarantee).

It reads FlatZinc, XCSP3, SMT-LIB, MPS, DIMACS, OPB/WBO and WCNF, and MiniZinc models through the included solver integration. Any model, whether built in Kotlin or read from a file, saves to and loads from JSON without loss.

## What results guarantee

- Solutions are checked against the original model, not the solver's transformed view. Finite engines enforce Boolean and integer constraints by propagation, and the final point is checked again against the original relations. Real coordinates are checked in exact rational arithmetic, or within the tolerance a source declares, such as MPS feasibility tolerances.
- A point that already passed the same check, for the same model, objective and tolerance policy, is not checked again when it moves between workers. Only restrictions the new consumer adds are checked. Changed coordinates or a transformed model get a fresh check.
- An interrupted check proves nothing. A portfolio publishes nothing from it. A standalone complete search keeps a leaf whose recheck the deadline cut short, and that leaf rests on the engine's own propagation alone.
- Bounds, optima and infeasibility rest on a completed engine run (exhaustive search, propagation, conflict learning) or on an LP certificate checked in exact rational arithmetic. These are in-memory attestations of the engine that produced them.
- Under a configured tolerance, optimality and infeasibility are numerical claims about that tolerance, not exact proofs.
- Where an exact derivation of a bound or refutation is captured, it is rechecked against the original source in memory. No claim is checked by an independent external checker.

## The LP engine

- Klause has its own revised simplex written in Kotlin: a floating-point dual simplex with Devex pricing, sparse LU factorization and Forrest-Tomlin updates.
- The LP lives inside search. Bound changes and backtracking update the same warm basis instead of starting a new solve at every node.
- The heavy work runs in floating point, and exact rational arithmetic is spent only on certification. An LP result is checked exactly before it prunes a node or supports a claim, whether it is a feasible point, an objective bound, an infeasibility proof or an unbounded ray. A result that fails the check is never used as a proof, and an uncertified floating result only guides branching and heuristics.
- The same engine carries integer and continuous columns, so it serves MIP, CP relaxations and SMT real arithmetic, and works alongside propagation and local search.
- LP bounds and conflicts are explained by the source constraints, so they feed conflict learning the same way propagation does.
- Strict inequalities are handled exactly, as SMT linear arithmetic requires.
- Exact mode extends this to floating-point formats. FlatZinc floats are solved by exact theory search instead of a grid, MPS models are solved with zero tolerance, and every reported point satisfies the source constraints exactly. Without it, MPS points are checked within the model's configured tolerance.

## Compared with other solvers

Specialised solvers are mature and usually faster on problems squarely in their own domain. Klause trades some of that speed for breadth and checked results. It bundles no native solver library, so it runs wherever Kotlin Multiplatform does.

- CP-SAT works over Booleans and integers; Klause also handles reals, unbounded integers and nonlinear arithmetic in the same model.
- Z3 decides logical theories; Klause adds optimization, global constraints and local search while still deciding linear integer and real arithmetic exactly.
- Gurobi and HiGHS accept floating-point results within tolerances; Klause solves in floating point too, but certifies LP proofs in exact arithmetic and checks every published point against the original model, exactly or within the tolerance the source declares.

## Limitations

- On large MIPs, hard SAT instances or SMT benchmarks, established solvers are usually much faster.
- Nonlinear integer and transcendental arithmetic are incomplete: Klause can find and check solutions, but may stop with an unknown result instead of a proof. Nonconvex quadratic objectives get no global optimality claim when a branch stays unresolved.
- SMT-LIB support covers quantifier-free arithmetic only. Bit-vectors, arrays, strings, quantifiers, uninterpreted functions and incremental solving are rejected.
- Proofs are checked in memory, and only where an exact derivation is captured; other refutations and bounds rest on the completed engine run. Klause cannot yet export proof files for an independent checker.
- Not every kind of model has every search style yet; local search and portfolio coverage still have gaps.

## Documentation

User documentation is at [eignex.com/docs/klause](https://eignex.com/docs/klause/). The internal developer documentation is a book; start at its [table of contents](docs/README.md).

## Build

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

The JVM launcher requires JDK 25 or newer.
