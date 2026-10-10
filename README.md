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

Klause is a Kotlin Multiplatform constraint solver for Booleans, finite-domain
integers and linear reals. It combines backtracking, constraint propagation,
linear-programming relaxations, local search and portfolios. Use it through the
Kotlin/Java DSL, the JVM CLI or a native executable.

It reads FlatZinc, XCSP3, SMT-LIB linear arithmetic, MPS, DIMACS, OPB/WBO and WCNF.
MiniZinc source models use the included solver integration.

## Documentation

User documentation is hosted at [eignex.com/docs/klause](https://eignex.com/docs/klause/).
Repository `docs/` contains [internal developer documentation](docs/README.md)
and architecture contracts.

- [System architecture](docs/architecture.md) and [search](docs/search.md).
- [LP architecture](docs/lp/architecture.md) and [exact certification](docs/lp/certification.md).
- [Frontend and result contracts](docs/formats.md).
- [Benchmarking](docs/benchmarking.md) and [development](docs/development.md).

## Build

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

The JVM launcher requires JDK 25 or newer. See [CLI setup](docs/cli.md#build-and-run)
for runtime options and native builds.
