# Input semantics

Format parsing and lowering live under
[formats](../klause/src/commonMain/kotlin/com/eignex/klause/formats/).
The [CLI](cli.md#formats-and-dispatch) lists dispatch names and extensions.

## Finite and open models

Source routing chooses a finite solve, an open-theory solve, a refutation or an
unsupported result. It can first prove that open integer sides have finite bounds.
That proof uses a slice of the shared preparation allowance and the solve's
cancellation token. Failure to close a bound must not replace it with a guessed
finite domain.

`-e` selects a finite engine and can control an open local-search request. Models
routed to a complete open theory use the shared theory solve independently of
that choice. SMT-LIB and source-exact MPS can fall back to open local search for
an unsupported fragment or objective; it supplies witnesses without a completeness
claim. Exact FlatZinc declines unsupported open fragments.
`--param open-bound-proof=false` disables the
routing proof for MPS, SMT-LIB and exact FlatZinc, allowing a supported source to
be measured through its open route. Unsupported syntax or a declined exact
operation remains undecided.

## FlatZinc floats

FlatZinc first preserves finite float choices from constant-array selection and
singleton domains. Supported connected operations lower to exact linear rows with
Boolean structure. This includes comparisons, strict/reified comparisons, absolute
values, minimum/maximum, constant-array selection, multiplication/division by
constants, and products with a finite selected operand.

Finite integer float images retain affine maps to source integer values. Fixed
integer selections and products can be eliminated while preserving source values
and output reconstruction. These maps must survive preparation and source checks.

A component requiring approximation, or more than 4096 finite alternatives, uses
integer buckets at a configurable resolution. Repeated array entries count toward
the alternative limit. Continuous components without finite choices also use the
grid under default lowering. Unconstrained floats outside the objective can remain
continuous. Unrestricted continuous-by-continuous products are outside linear
arithmetic.

Bucketed search decides a finite grid. It may miss solutions between grid points;
rounding can produce candidates violating the source constraints. Source-check a
returned candidate before assigning it feasibility credit. Even a source-valid
grid witness does not turn a grid optimum or refutation into a source proof.

`--exact` preserves continuous variables and open bounds, routes supported linear
satisfaction through the shared real/mixed theory, and requires exact certification
for continuous objectives. It declines unsupported float operations and expansion
above the alternative limit. Arithmetic overflow during lowering also declines
with a diagnostic.

See [FlatZincFloatPolicy](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincFloatPolicy.kt)
and [FlatZincIntegerFloatImages](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincIntegerFloatImages.kt).

## SMT-LIB

The frontend supports quantifier-free linear integer and real arithmetic, including
difference fragments, Boolean structure and supported minimize/maximize objectives.
The theory packages cover difference arithmetic, LIA and LRA/LIRA. Bitvectors,
arrays, strings, quantifiers and unrestricted non-linear arithmetic are outside
this frontend's supported theory surface.

Asserted and reified equalities between an integer conditional result and a constant
can lower directly to Boolean tests of its branches. Completed conditional definitions and equality
results are shared within one parse; nested definitions are traversed iteratively.
A parse limits this expansion to 65,536 visits, after which comparisons retain
their arithmetic encoding. Numeric branch definitions remain enforced whenever
a surviving constraint, objective or shared reified predicate needs them. Unused
fresh definitions are omitted after following these dependencies to a fixpoint;
source declarations and arithmetic leaves remain authoritative, including open
defaults and sparse decision-list selectors. Fresh reified predicates belonging
only to omitted definitions are fixed false, so shared search does not branch
on their unconstrained values.

Optimization emits `; objective=<value>` for each incumbent in the source objective's
direction and `; optimizationStatus=<status>` at completion, including `optimal`,
`best-found` and `unbounded`. These comments appear without `-s`; `sat` alone
does not establish optimality.
Exact theory objectives use arbitrary-precision integer or reduced `numerator/denominator`
text, including values outside `Long` and non-terminating rational values.
Finite witnesses report objectives from certified real values and exact integer
sums. Supported linear integer optimization uses the complete theory route when
its objective range can exceed the consecutive-integer range of binary64.

Open integers retain arbitrary-precision source bounds. A linear relaxation witness
does not establish integrality: shared search splits fractional integers at exact
floor/ceiling thresholds. Strict real inequalities require an interior witness or
an exact strict-feasibility refutation. A closure point on a strict boundary is
insufficient. Budgets can leave the solve unknown; support for linear syntax is
not an unlimited completeness guarantee for every integer model.

## MPS

The MPS parser retains decimal source metadata, integer markers, bounds and
objective sense. The lowered LP may restate continuous coefficients in binary64.
`sourceExact` records exact source correspondence; `toleranceDifference` identifies
a lowering difference relevant to tolerance publication. Rounded or oversized
pure-integer rows are rebuilt from source decimals; oversized objectives can use
an auxiliary defining row. Integer values and lower-bound shifts keep exact
authority beyond `2^53`.

Finite default MPS solves can accept a continuous leaf by source tolerance. Rows use

```text
1e-7 * max(1, abs(bound), sum(abs(a(i) * x(i))))
```

and column bounds use `1e-7 * max(1, abs(bound))`. Nonfinite row activity or
magnitude is checked again exactly against this scaled tolerance. The separate LP
gap test uses lowered objective units and accounts for reduced-cost movement, row
residuals and objective rounding. The rendered objective uses source decimal
coefficients at the returned point.

`TOLERANCE_OPTIMUM` differs from `ATTAINED_OPTIMUM`. A tolerance-accepted float
point is not an exact witness. Optimum publication requires no
`toleranceDifference`; infeasibility and unboundedness require `sourceExact`.
`--exact` requires exact certificates and declines a lowering that differs from
the source. Exact source witnesses use exact decimal checks when correspondence
holds, and decimal tolerance otherwise.

The open MPS route declines purely continuous models and inexact source lowering.
Complete theory optimization requires an integral objective without continuous
or Boolean cost and a bound row remaining inside the supported theory. Other
objectives can use open local search for incumbents, with source-exact lowering
still required. Check the frontend's route and decline reason before attributing
a missing result to the simplex.

See [MpsCompiler](../klause/src/commonMain/kotlin/com/eignex/klause/formats/mps/MpsCompiler.kt)
and [MpsMode](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).

## Other finite formats

DIMACS CNF supplies Boolean clauses. WCNF supplies weighted partial MaxSAT.
OPB/WBO supplies pseudo-Boolean constraints and objectives, including supported
product and soft-constraint encodings. XCSP3 supplies finite-domain variables,
supported globals and optional objectives. Each frontend retains its source
objective orientation and output names. Unsupported constructs decline through
the common solve surface.
