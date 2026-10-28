# Smoke corpus

This directory contains small parser and solve
fixtures grouped by format. Larger or non-redistributable collections are fetched
through the catalog, with their license and exclusion policy in the collection
declaration. See [corpus management](../README.md#catalog-and-corpus).

| Directory | Contents and origin |
|---|---|
| `dimacs` | SATLIB-style small random 3-SAT samples; self-authored pigeonhole, implication-chain, bipartite-colouring and weighted MaxSAT cases |
| `opb` | Self-authored set-cover/cardinality; academic PB/MaxSAT competition samples `sporttournament06.opb` and `queens4-soft.wbo` |
| `schema` | Self-authored campaign and roster schemas; regenerate campaign with `:klause-bench:dumpSchema` |
| `smtlib` | Self-authored linear integer/real satisfaction, optimization, infeasibility, disjunction and exact-rational cases |
| `xcsp3` | Self-authored magic-series, sum optimization, pair-cover cost, magic-square and graph-colouring cases |
| `extensional-overlap` | Self-authored Apache-2.0 table/MDD shared-tuple contradiction and reconvergence controls |
| `mps` | Self-authored integer and continuous optimization, equality feasibility, infeasibility, unboundedness and open decimal witness controls |
| `mps-linear-source-v1` | Self-authored Apache-2.0 exact decimal, indicator, wide-coordinate and mixed primal-point source controls; original sense, constants and integral LP columns retain their source mappings |
| `product-aliases` | Self-authored MiniZinc repeated-role products and fixed powers, including reified comparisons, zero/identity powers and standard truncated negative powers; Apache-2.0 |
| `divmod` | Self-authored Apache-2.0 signed quotient/remainder, wide Euclidean, joint conflict and equivalent linear controls; MiniZinc input is `klause-mzn-lib/test-models/divmod.mzn` |
| `lp-component-split` | Self-authored dense bounded block systems for the explicit measurement suite |

MiniZinc smoke models live under
[klause-mzn-lib/test-models](../../klause-mzn-lib/test-models) and are referenced
by the `mzn-smoke` suite. They are not duplicated in the smoke corpus.

`xcsp3/pair-cover-alias-costs.xml` retains `o = x + y + z` over the sparse
pair-cover domains and minimizes `o`. `pair-cover-alias-replaced.xml` retains the
same variables and constraints but minimizes the sum directly. Both self-authored
Apache-2.0 controls have optimum 6 at `(0,3,3,6)`. Their distinct identities compare
cost exposure and retained output-bound propagation without changing feasibility.

The width-`n` component-split matrix is `nI + 11^T` with RHS `2n`, giving exact
solution `x = 1`. The linked MiniZinc control adds a redundant row joining blocks.
These fixtures are explicit experiments rather than CI smoke selection.

## Polynomial-real SMT fixtures

[QF_NRA version 1](qfnra-v1/manifest.json)
contains 37 bounded core, 24 frozen holdout and five separate extension controls.
Every source is original work by Eignex Klause contributors under Apache-2.0;
no third-party corpus bytes are redistributed. The manifest fixes suite/name,
path, original SHA-256, mathematical expectation, membership class, reason,
attribution and license. Mathematical expectations are not executed verification.
Catalog core/holdout expectations retain SAT/UNSAT; extension expectations remain
unknown even when a reference accepts the extension.

Polynomial cases declare only Real/Bool variables and use exact rational/decimal
constants, polynomial arithmetic and Boolean comparisons. Constant nonzero division
is rational coefficient syntax. Symbolic division, mixed integer sorts, negative
powers, transcendental functions and multiple-query sessions have separate identities.
The fetched QF_NRA archive preserves its per-family attribution/licenses and is
not certified as polynomial by its logic tag. See the benchmark README for
[named selection](../README.md#selection-and-named-sets) and
[reference solving in klause-lab](../README.md#klause-lab).

[Canonical source variants](qfnra-source-canonical-v1/manifest.json)
retain degree-drop and equal-degree common-root cases with canonical `(- 1)`
constants. Their manifest fixes each variant's source hash, original identity/hash
and exact syntactic transformation under the original Apache-2.0 attribution.
Bare `-1` in the two frozen originals is accepted by recorded Z3 but is a source
name under the dedicated polynomial parser; source loading declines explicitly.
Variants have a separate suite/set; original bytes, identities and memberships
remain frozen. Auditing all 66 originals for unquoted negative numeric atoms found
only these two sources; that narrow audit does not prove every other source form
is admitted or decided.

## Nonlinear integer SMT fixtures

[Nonlinear integer version 1](nonlinear-integer-v1/manifest.json)
fixes twelve self-authored Apache-2.0 inputs covering bounded products, powers,
Int/Real composition, Boolean correlation, lexical helpers and assumptions,
arbitrary-width arithmetic and open integer search limits. The suite and named
set share these identities. SAT/UNSAT expectations describe source mathematics;
incomplete search can return UNKNOWN, including the integer equation `n*n = 2`.
Every manifest entry pins the exact original bytes, attribution and license.

## Objective fixtures

The self-authored Apache-2.0 [objective-cli-v1 manifest](objective-cli-v1/manifest.json)
fixes original mixed product, rational, algebraic, strict-limit, unbounded and
infeasible SMT inputs. Affine and zero-square objectives retain the same source
constraints and both directions.

The self-authored Apache-2.0 [objective-affine-v1 sources](objective-affine-v1/manifest.json)
retain `x*x=y`, `-1<=x<=1` and both directions of `Long.MAX_VALUE+y`. Their exact
optima are `9223372036854775807` and `9223372036854775808`, which share a binary64
projection. The manifest fixes source hashes. Its captured square schema exercises named continuous float compilation; declared
`0<=y<=1` bounds follow from those same original source constraints.

The self-authored Apache-2.0 [objective-scopes-v1 fixtures](objective-scopes-v1/manifest.json)
retain finite integer domains, AllDifferent, fixed `x=y=1`, `x*x=y` and explicit
Boolean/integer pins. Two scopes change the constant and pins while retaining
separate Boolean, integer and real declaration-zero identities. Their exact costs
are `31/2` and `27/2` in both directions. Schema and condition hashes accompany
the immutable original source hashes; generated execution records stay external.

The self-authored Apache-2.0 `wcnf-core/cover-*` fixtures share four positive
covering rows with optimum 1. `cover-dominated` has strictly cheaper covering
replacements; `cover-tied` retains tied costs and `cover-other-reader` excludes the
common covering column through a negative exclusion clause. They support focused dual-fixing
comparisons.

## Other controls

The self-authored [weighted penalty complement fixture](dimacs/weighted-penalty-complements.wcnf)
in `wcnf-core` combines nonuniform unit/nonunit costs, a hard clause with repeated
literals, a source alias and a tautological soft clause. Its exact optimum is 5
at `(x1, x2, x3) = (1, 0, 1)`.

The synthetic `continuous-arithmetic` MiniZinc controls are authored for Klause.
They retain fixed float power, square, independent product and chained product relations over declared
real intervals, with objectives attained at exactly representable endpoints. They
exercise grid and exact arithmetic paths and original MiniZinc witness checks; grid objective
and exhaustion claims retain their approximation limits.

The self-authored `klause-bench/smoke-corpus/mps-numerical` sources exercise open
integer numerical optima, marker versus general bounds, wide attained coordinates,
constant contradictions, exhaustive integer gaps, integer infeasibility beside an
unbounded relaxation and integral improving rays. Precision controls retain decimal
rows, objectives and indicators, including nonzero numerical underflow refusal.
Integer guard fixtures cover fixed zero/one triggers, exhaustive conditional
refutations and the inactive regions of one-sided/free declarations. They contain
no external data and use the repository's Apache-2.0 license.
