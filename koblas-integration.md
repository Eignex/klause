# Koblas integration

Klause owns sparse LU, reach, pivot policy, Forrest–Tomlin updates, checked arithmetic and scratch
lifetime. Koblas supplies validated CSC containers, indexed/vector Level 1 kernels and generic sparse
primitives. There is one floating simplex with exact certification; vendor selection does not change
numerical authority or create another solver.

## Dependency provenance

Integration base: klause `445d1bd5cdd839424d5c1b7a3768e4d7cb5deeea`.
Inspected koblas main: `75126edfa77e8dca53d2c496517e918a7ac16a9b`.
Every Kotlin file in the published source jars matches the inspected koblas main, including its JVM
hardware-FMA guard. Publication completed before a shell continuation error failed the overall
[release run](https://github.com/Eignex/koblas/actions/runs/35399195989). Each listed binary and source
artifact was retrieved and checked separately.

The build pins immutable timestamps for both the KMP root and every platform module. A root timestamp
alone is insufficient because its Gradle metadata redirects to mutable platform snapshots. Strict
platform constraints propagate into klause publications.

All coordinates use group `com.eignex` and version prefix `0.1.1-20260918.215912-`:

| Module | Build | Binary SHA-256 |
|---|---:|---|
| koblas | 232 | `6bcd9279cc318d7c89a40ff4ce54015f8773bc6f199d5862fb80a4bd5a91df0f` |
| koblas-jvm | 235 | `eefa41e82ca348c3b75bd34396321084a5f000bee27dd59c9d7448201a379721` |
| koblas-linuxx64 | 231 | `90b181e95795875ecac5991db7f9c2337f2a30f59f64edc6e53ca6323a87677e` |
| koblas-linuxarm64 | 232 | `d7f67015fe514d45a900f76ca9eeddd81fb36c887b63775412d5905a5fb91787` |
| koblas-macosarm64 | 240 | `8ed9bc8b55325e0c918f4372beee2a4a1f49e07823398afb2871b4f283b03eb7` |

The build does not consult Maven Local. For source integration, use Gradle's supported composite
substitution with an isolated checkout at the revision under test:

```sh
./gradlew --include-build /path/to/isolated/koblas :klause:jvmTest --max-workers=2
```

Record the checkout revision and the resulting artifact hashes separately from published-artifact
checks. Do not publish over a shared `SNAPSHOT`, or commit machine-specific paths. Dependency updates
must update every platform pin together and repeat provenance and published-metadata verification.

`kumulant:0.3.3` owns its dense numerical types and has no koblas dependency. Its newer development
checkout is a separate migration and is not substituted into this build.

## Consumer contracts

| Consumer | Integration contract |
|---|---|
| `simplex.basis` | Zero-based sorted unique CSC, owned source/factor copies, explicit stored zeros, retained permutations and snapshots. `BasisScratch` lends exact-size dirty arrays exclusively and returns them in `finally`; retained factors copy before return. |
| Sparse slice workflows | Validate windows, indices, capacity and overlap before mutation. Unique scatter/touched support, first-touch order, structural zeros until explicit compaction, positive-zero clearing. Checked scatter delegates to generic primitives; checked dot preserves scalar input order and underflow/nonfinite diagnostics. |
| `lp.engine` | Pricing and column updates use indexed kernels; checked workflows retain solver semantics. Scaling/refinement keep guarded powers of two, exact accumulation and source maps. No dense vendor Level 2/3 call is required. |
| `util.SparseSlices` | Shared validated slice workflows serve the LP engine and basis without adding a solver-layer dependency. Other array/permutation/domain helpers have no koblas consumers. |
| CLI and CI | JDK 25, native-access permission and optional Vector API module. Linux Native tests are separate in CI; release opts into arm64 targets. BLAS libraries are installed by the host, never extracted from klause artifacts. |
| Tests and capture tools | Common basis/LP tests cover ownership and certification. Real traces verify factorization and update observations plus FTRAN/BTRAN residuals. Kernel composition and vendor identity are diagnostic fields, not promises about reduction order. |

Unchecked reductions may reassociate or fuse arithmetic according to the selected implementation.
Klause does not use vendor `iamax` to choose pivots; masked maximum and candidate positions retain
their own finite-value and ordering contract. Exact proof checks, work/cancellation budgets, repair
receipts and scoped replacement ownership remain solver responsibilities.

## Runtime support

JVM launchers and Gradle execution tasks enable `jdk.incubator.vector` and
`--enable-native-access=ALL-UNNAMED`. `-Pkoblas.noSimd=true` omits the module for portable-kernel
verification. Embedded JVM callers supply these runtime options themselves.

Koblas selects one immutable platform composition. Linux x64 prefers oneMKL or AOCL by CPU vendor;
Linux arm64 prefers ArmPL; both fall back to installed OpenBLAS. macOS uses system Accelerate.
Bindings use LP64 integers and one compute thread per call. Usual sonames and fixed installer
prefixes are checked; `koblas.vendor` exposes the resolved path/version. No library means portable
Level 1, containers and sparse primitives remain usable; dense Level 2/3 raises rather than falling
back to portable arithmetic. Klause has no backend registry or bundled vendor payload.
