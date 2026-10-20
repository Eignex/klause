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

Klause is for applications that need one solver for many kinds of models and want to run it inside a Kotlin or JVM process. A model is described once. Complete search, local search, LP relaxations and arithmetic theories then work on that same model, alone or together in a portfolio, and every result they publish is checked against the original model before it is returned.

It reads FlatZinc, XCSP3, SMT-LIB, MPS, DIMACS, OPB/WBO and WCNF, and MiniZinc models through the included solver integration.

## How it differs

Specialised solvers such as CP-SAT, Z3 and HiGHS are mature and usually faster on problems squarely in their own domain. Klause trades some of that speed for breadth and checked results.

- **Compared with CP-SAT**, which works over Booleans and integers, Klause also handles real variables, unbounded integers and nonlinear arithmetic in the same model, and can run local search on the same models.
- **Compared with Z3**, which decides logical theories, Klause adds optimization, finite-domain global constraints, local search and MIP-style LP relaxations, while still deciding linear integer and real arithmetic exactly.
- **Compared with HiGHS**, which solves LP and MIP in floating point, Klause certifies LP results in exact rational arithmetic before using them as proofs, and combines MIP with propagation and local search.
- **Compared with all three**, Klause is written in Kotlin and bundles no native solver library, so it runs wherever Kotlin Multiplatform does.

## Documentation

User documentation is at [eignex.com/docs/klause](https://eignex.com/docs/klause/). The internal developer documentation is a book; start at its [table of contents](docs/README.md).

## Build

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

The JVM launcher requires JDK 25 or newer.
