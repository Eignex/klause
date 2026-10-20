# 29. Numerical dependencies

The last chapter of Part V looks below the mechanisms described so far, at the
external libraries they build on. These libraries supply data containers and
kernels within Klause's owned LP engine. They do not select source models, solving
routes or result semantics. This chapter explains what comes from each dependency,
how it is pinned, and the consumer and runtime obligations at that boundary. The
engine and its working state are described in [chapter 26](26-lp-architecture.md),
result authority in [chapter 27](27-lp-certification.md).

Klause owns sparse and small dense LU, reach, pivot policy, Forrest–Tomlin updates, checked arithmetic and scratch
lifetime. Koblas supplies validated CSC containers, indexed/vector Level 1 kernels and generic sparse
primitives. There is one floating simplex with exact certification; vendor selection does not change
numerical authority or create another solver.

## Dependency provenance

### Koblas

The [build](../klause/build.gradle.kts) pins immutable timestamps for both the KMP root and every platform module. A root timestamp
alone is insufficient because its Gradle metadata redirects to mutable platform snapshots. Strict
platform constraints propagate into klause publications.

The pins consume Koblas revision `74cf0237084597cce8eeef47ddd9c3613c30cf8e`,
published by its [snapshot workflow](https://github.com/Eignex/koblas/actions/runs/38446077479).
Koblas coordinates use group `com.eignex`:

| Module | Version | Binary SHA-256 |
|---|---|---|
| koblas | `0.1.1-20261013.182449-263` | `4bb78bbe9448b937e2bf88601e22c9414c5d8000b5ec75024be2d9c8444161b5` |
| koblas-jvm | `0.1.1-20261013.182449-266` | `36c06138be124ab6df809e7f9dde8f5dad4efd890585fc1350724b26662bd57d` |
| koblas-linuxx64 | `0.1.1-20261013.182449-262` | `27020e001be9346a675c0246b053e10f71d68e2577a139c3d99715d94b3c6492` |
| koblas-linuxarm64 | `0.1.1-20261013.182449-263` | `26224afb64a272b51e723bacca20e1b5e01b20473cb42d3f6b66c454eabd1004` |
| koblas-macosarm64 | `0.1.1-20261013.182206-286` | `8534ca098a41e671db582d14f664770895ec9b84b845d85f72ce80e12cadc36f` |

The build does not consult Maven Local. For source integration, use Gradle's supported composite
substitution with an isolated checkout at the revision under test:

```sh
./gradlew --include-build /path/to/isolated/koblas :klause:jvmTest --max-workers=2
```

Dependency updates must update every platform pin together.

### Kumulant

Kumulant supplies online statistics and adaptive bandits. Its contextual-bandit
features use Koblas vector types, so Klause strictly pins the Koblas coordinates
above for both direct and transitive consumers.

The pins consume Kumulant revision `9df19df5c48e2cce2caf0a612e51e39430903352`,
published by its [snapshot workflow](https://github.com/Eignex/kumulant/actions/runs/38456722493).
Kumulant coordinates use group `com.eignex` and version prefix `0.3.4-20261013.194059-`:

| Module | Build | Binary SHA-256 |
|---|---:|---|
| kumulant | 67 | `81bf47d09d6135b0857fc49510d17fbeac5dd958c5932753656d021bdfb78bae` |
| kumulant-jvm | 67 | `0b8e8e18088fca26f6045879083cbc11bd1a571dffe36b8cf0a90a1f1fab4c23` |
| kumulant-linuxx64 | 67 | `844f4b33057686b7ab47898757ae053f3aa377a508bc93cd0c7fbfc27ffa3a1a` |
| kumulant-linuxarm64 | 67 | `a705527909d08e53a8b1a05f1051da970980a6183ed2aea315ba9cb8fa6f5416` |
| kumulant-macosarm64 | 66 | `f0ce4c7f2f76c8aae200f8b443d4c4d2ad211733ebc540e7854916425ba1678c` |

## Consumer contracts

| Consumer | Integration contract |
|---|---|
| `simplex.basis` | Zero-based sorted unique CSC, owned factor copies, explicit stored zeros, retained permutations and fixed-dimension ownership. Dense LU uses windowed GER in owner-local buffers; arithmetic guards and failed-attempt work stay in Klause. |
| `util.SparseSlices` | Validated slice workflows shared by the LP engine and basis: windows, indices and overlap are checked before mutation, and checked dot keeps input order and nonfinite diagnostics. |
| `lp.engine` | Pricing and column updates use indexed kernels. Scaling and refinement keep guarded powers of two and exact accumulation. No dense vendor Level 2/3 call is required. |

Unchecked reductions may reassociate or fuse arithmetic according to the selected
implementation, so Klause does not use vendor `iamax` to choose pivots. Exact proof
checks, work and cancellation budgets remain solver responsibilities. The `GerWindow`
route always uses portable panel kernels, with Klause's overflow, underflow and
finite checks around each call.

## Runtime support

JVM launchers and Gradle execution tasks enable `jdk.incubator.vector` and
`--enable-native-access=ALL-UNNAMED`. `-Pkoblas.noSimd=true` omits the module for portable-kernel
verification. Embedded JVM callers supply these runtime options themselves.

Koblas selects one platform composition with portable kernels; eligible dense calls
can use an installed host library (oneMKL or AOCL on Linux x64, ArmPL or OpenBLAS on
Linux arm64, Accelerate on macOS), with LP64 integers and one thread per call.
`koblas.vendor` exposes the resolved library. Klause bundles no vendor payload.

---

Previous: [28. Exact arithmetic kernels](28-exact-arithmetic.md) · [Contents](README.md) · Next: [30. Library API](30-api.md)
