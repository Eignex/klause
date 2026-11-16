# SMT-LIB conformance fixtures

This inventory covers first-order quantifier-free inputs over Core, Ints, Reals,
Reals_Ints, FixedSizeBitVectors, ArraysEx, free sorts/functions and algebraic
datatypes. Floating point, String/RegLan, term quantifiers and higher-order maps
are excluded. First-order sort parameters and polymorphic declarations are
included. Admission, solving, reconstructed values and command sessions are
separate observable behaviors; successful parsing alone establishes no solve or
model claim. `unknown` records an inconclusive solve.

The conformance additions below are pending execution on the assembled feature
stack. Entries describe the required contract, not an assertion that untested
draft implementations satisfy it. The flat per-symbol catalogue is
[`coverage.tsv`](coverage.tsv); its references resolve to the fixture groups below
and the focused test classes in this directory. A reference of the form
`TestClass#test name` identifies one exact test; unqualified class references
identify the associated focused family suite.

## Specification identity

The inventory was audited against these primary specifications on 2026-11-16:

| Source | Pinned revision | Relevant contract |
| --- | --- | --- |
| [SMT-LIB reference](https://smt-lib.org/papers/smt-lib-reference-v2.7-r2025-07-07.pdf) | 2.7, release 2025-07-07 | Lexicon and terms, sections 3.1–3.6; command grammar, 3.11; command semantics, 4; polymorphism, 5 |
| [Core](https://smt-lib.org/theories-Core.shtml) | Last updated 2024-07-21 | Bool and polymorphic Boolean operators |
| [Ints](https://smt-lib.org/theories-Ints.shtml) | Last updated 2026-01-16 | Integer arithmetic, including `**` |
| [QF_EIA](https://smt-lib.org/logics-all.shtml#QF_EIA) | Last updated 2026-03-18 | Quantifier-free integer arithmetic with symbolic exponentiation |
| [Reals](https://smt-lib.org/theories-Reals.shtml) | Last updated 2024-07-21 | Exact real arithmetic and total division |
| [Reals_Ints](https://smt-lib.org/theories-Reals_Ints.shtml) | Last updated 2024-07-21 | Mixed arithmetic, floor and integrality |
| [FixedSizeBitVectors](https://smt-lib.org/theories-FixedSizeBitVectors.shtml) | Last updated 2025-09-09 | Core bitvectors, conversions and overflow |
| [QF_BV](https://smt-lib.org/logics-all.shtml#QF_BV) | Last updated 2025-02-25 | Derived bitvector operators and decimal bitvector literals |
| [ArraysEx](https://smt-lib.org/theories-ArraysEx.shtml) | Last updated 2024-07-21 | Polymorphic select/store and extensionality |

These are version identities, not live dependencies. Tests perform no network
requests, download no fixtures and provision no reference solver. The formulas
in `SmtQfConformanceTest.kt`, `SmtQfValueConformanceTest.kt` and
`SmtQfResourceConformanceTest.kt` were written for this repository from the listed
semantic laws and existing local operator examples. They are not copied benchmark
inputs. Stable identities are the complete test names and the C, V, R and P rows
below. The repository licence applies.

## Term and operator inventory

Every symbol in a row is in scope. Associative forms, chainable comparisons and
pairwise `distinct` retain the attributes declared by their specifications.
Malformed arities, sorts and indexes must be checked even in unused definitions
or Boolean branches whose value is already known.

| Family | Required symbols or forms | Semantic boundaries | Focused coverage |
| --- | --- | --- | --- |
| Core values and connectives | `true`, `false`, `not`, `=>`, `and`, `or`, `xor` | Bool cardinality two; implication associates right; xor parity | `SmtLibTest`, `SmtLibScopeTest`, `SmtLibBooleanDefinitionsTest` |
| Core sort-polymorphic operators | `=`, `distinct`, `ite` | Equality chains; all-pairs disequality; both branches have compatible sorts; composites use extensional/constructor meaning | `SmtLibDistinctTest`, `SmtGroundFunctionsTest`, `SmtGroundArraysTest`, `SmtGroundDatatypesTest`, C02/C04/C06/C08 |
| Integer literals and ring operations | numerals, unary/binary `-`, `+`, `*`, `abs` | Arbitrary precision; no implicit finite domain; multiplication need not be linear | `SmtLibTest`, `SmtLibPolynomialTest`, `SmtLibProductsTest` |
| Integer division | `div`, `mod` | Nonzero divisor: Euclidean nonnegative remainder for either divisor sign; zero divisor: total underspecified functions, with evaluated-argument congruence | `SmtLibTest`, `SmtArithmeticReductionTest`, C07 |
| Integer powers and divisibility | `**`, `(_ divisible n)` | Symbolic exponents; `0^0=1`; negative exponents follow Ints; divisor index positive and arbitrary precision | `SmtArithmeticReductionTest`, C11 |
| Arithmetic comparisons | `<`, `<=`, `>`, `>=` over Int/Real | Chainable; exact strictness; integer-to-real promotion only where admitted | `SmtLibTest`, `SmtLibRealTest`, `SmtLibScopeTest` |
| Real literals and operations | numerals, decimals, unary/binary `-`, `+`, `*`, `/` | Exact rationals; nonlinear expressions; division total at zero with congruent choices | `SmtLibRealTest`, `SmtLibPolynomialTest`, `SmtArithmeticReductionTest` |
| Mixed arithmetic | `to_real`, `to_int`, `is_int` | `to_int` is floor, including negative/nonlinear arguments; no Real-to-Int coercion | `SmtLibRealTest`, `SmtArithmeticReductionTest` |
| Bitvector sort and literals | `(_ BitVec m)`, `#b…`, `#x…`, `(_ bvX m)` | Positive width; declared width preserved past machine precision; decimal literal requires `X < 2^m` | `SmtLibBitVectorTest`, `SmtBitVectorReductionTest`, `BitVectorCircuitTest` |
| Bitvector wiring | `concat`, `(_ extract i j)`, `(_ repeat i)`, `(_ zero_extend i)`, `(_ sign_extend i)`, `(_ rotate_left i)`, `(_ rotate_right i)` | Bounds and result widths; arbitrary-size rotation index reduced modulo width; no machine shift masking | `BitVectorCircuitTest`, `SmtBitVectorReductionTest` |
| Bitvector bitwise | `bvnot`, `bvand`, `bvor`, `bvnand`, `bvnor`, `bvxor`, `bvxnor`, `bvcomp` | Listed associative forms only; `bvxnor` binary; `bvcomp` returns one bit | `BitVectorCircuitTest`, `SmtBitVectorReductionTest` |
| Bitvector arithmetic | `bvneg`, `bvadd`, `bvsub`, `bvmul`, `bvudiv`, `bvurem`, `bvsdiv`, `bvsrem`, `bvsmod` | Width-one signs; division by zero; minimum signed word divided by minus one; signed remainder/modulo differ | `BitVectorCircuitTest`, `SmtLibBitVectorTest` |
| Bitvector shifts | `bvshl`, `bvlshr`, `bvashr` | Shift distance is unsigned; distances at/above width fill with zero or sign bits | `BitVectorCircuitTest`, `SmtLibBitVectorTest` |
| Bitvector comparisons | `bvult`, `bvule`, `bvugt`, `bvuge`, `bvslt`, `bvsle`, `bvsgt`, `bvsge` | Unsigned and signed order; Bool results | `BitVectorCircuitTest`, `SmtBitVectorReductionTest` |
| Bitvector overflow | `bvnego`, `bvuaddo`, `bvsaddo`, `bvumulo`, `bvsmulo`, `bvusubo`, `bvssubo`, `bvsdivo` | Detect mathematical overflow before modular truncation | `BitVectorCircuitTest`, `SmtBitVectorReductionTest` |
| Integer/bitvector conversions | `ubv_to_int`, `sbv_to_int`, `(_ int_to_bv m)` | Unsigned/signed interpretation; negative integer input wraps modulo `2^m`; private quotients remain open | `SmtLibBitVectorTest`, `SmtBitVectorReductionTest`, C07 |
| Free sorts | Nullary and positive-arity sort constructors; `define-sort` aliases | Applied sort identity; simultaneous local sort substitution; no recursive aliases or theory-symbol shadowing | `SmtGroundFunctionsTest` |
| Ground functions and predicates | Nullary/n-ary declarations, nested applications, qualified identifiers | Equal evaluated arguments have equal results; all admitted parameter/result sorts; total typed defaults | `SmtGroundFunctionsTest`, `SmtSemanticEqualityTest`, C01/C02/C03/C04/C08/C10 |
| First-order polymorphism | `declare-sort-parameter`, polymorphic function ranks and assertions | Assertions cover all monomorphic instances; sort parameters are not ordinary free sorts; finite necessary instances cannot alone justify general SAT | C09; polymorphism cases in `SmtGroundFunctionsTest` |
| Array operations | `select`, `store`, array equality/disequality and `ite` | Read-over-write; extensional witnesses; arrays allowed in both index and element sorts; no address enumeration | `SmtGroundArraysTest`, C01/C02/C03/C04/C05/C08 |
| Datatype values | Nullary/n-ary constructors, selectors, `(_ is c)` | Constructor disjointness/injectivity/exhaustion; wrong-constructor selectors total and consistent; finite cardinalities include field sorts | `SmtGroundDatatypesTest`, C03/C04/C05/C06 |
| Recursive datatypes | Recursive/mutual declarations, parametric instantiations | Well-founded declarations; finite constructor trees; no arbitrary depth bound; mutual recursive sort occurrences only at top of field sort | `SmtGroundDatatypesTest` |
| Datatype matching | `match`, constructor patterns, variable and `_` catch-all patterns | Exhaustiveness; one-level patterns; field binders lexical and distinct; qualified ambiguous nullary constructors | `SmtGroundDatatypesTest` |

## Language and source-model inventory

| Surface | Required behavior | Coverage owner |
| --- | --- | --- |
| Tokens | ASCII numeral/decimal/binary/hex grammar, semicolon comments adjacent to tokens, simple/quoted symbols, keywords, doubled-quote string escaping for commands | `SExprTest`, `SmtLibScopeTest`, `SmtLibSessionTest` |
| Namespaces | Quoted/bare canonical identity; duplicate declarations and illegal theory-symbol shadowing fail; declared/defined names beginning `.` or `@` reserved | `SmtLibScopeTest`, `SmtLibSessionTest`, C10 |
| Qualified/indexed identifiers | `(as identifier sort)` selects a compatible result rank; indexed operators and datatype testers remain identifiers inside qualification | `SmtGroundFunctionsTest`, `SmtGroundDatatypesTest`, `SmtBitVectorReductionTest` |
| Lexical binders | Parallel `let`; lexical nonrecursive definitions; capture-avoiding substitution; declaration order and unused-argument sorts checked | `SmtLibScopeTest`, `SmtGroundFunctionsTest`, `SmtLibSessionTest` |
| Annotations | General attributes parsed; closed `:named` subterms introduce nullary definitions in left-to-right post-order, with ordinary declaration lifetime and visibility within the same command | `SmtLibSessionTest` |
| Recursive definitions | Definitions constrain every argument, including unused functions; productive checked SAT/UNSAT controls; an unresolved definition cannot justify SAT by finite unfolding alone | `SmtLibSessionTest` |
| Declaration policy | Semantic numeric endpoints apply only to original numeric constants; composite IDs, function values and private encoding coordinates do not inherit them | `SmtLibSemanticsTest`, `SmtLibBitVectorTest`, `SmtGroundFunctionsTest`, `SmtGroundDatatypesTest` |
| Model reconstruction | Original declarations and source sorts; total function/default-array values; actual constructor trees; no public auxiliary IDs; exact rational/algebraic/symbolic authority retained | `SmtSourceModelTest`, `SmtSemanticEqualityTest`, C01/C03/C05/C07/C10/C32/C33 |
| Model syntax | Qualified abstract values, fresh function formals, no forward references to ordinary model definitions; monomorphic get-value terms; consistent reuse of prior abstract values | `SmtSourceModelTest`, `SmtLibSessionTest` |
| Publication | Source checks precede callbacks and final SAT/optimum publication, including finite shapes, native formulas and quadratic samples; rejection/unavailability/interruption removes unchecked terminal authority | `SourceWitnessValidationTest`, `SourceExecutionTest`, `SmtSemanticEqualityTest` |
| Resources | Admission and current execution allowances are independent; compound closure, circuit work and exact comparisons poll/charge cancellation; attachments retain no shared execution state | `SourceWitnessValidationTest`, family cancellation tests |

## Command inventory

The complete first-order command grammar in reference 2.7, section 3.11 and
appendix B, is accounted for below. `SmtLibSessionTest` owns command-state behavior;
CLI transcript tests own actual output and file-channel routing. The one-query
`SmtLib.parse` API and the streaming session API have distinct entry contracts.

| Commands | Required behavior |
| --- | --- |
| `set-logic` | Start-mode transition, current signature, `ALL`, transactional invalid/multiple logic errors |
| `declare-sort`, `define-sort`, `declare-sort-parameter` | Constructor arities, alias substitution, globally declared sort parameters, namespace/scope rules |
| `declare-const`, `declare-fun` | First-order mono/polymorphic ranks, original declarations preserved, no user overloads |
| `define-const`, `define-fun` | Lexical definitions with checked body/result rank; definition scope and declaration lifetime |
| `define-fun-rec`, `define-funs-rec` | Simultaneous recursive rank visibility and full defining obligations |
| `declare-datatype`, `declare-datatypes` | Constructor/selector/tester signatures, parameters, mutual declarations, well-foundedness |
| `assert` | Bool formula with current signature; retain original assertion syntax for inspection |
| `push`, `pop` | Nonnegative numeral counts, zero is a no-op, base level cannot be removed |
| `reset-assertions` | Drop assertions/extra levels and nonglobal user declarations, including base declarations; preserve options and logic symbols |
| `reset` | Restore initial state, options/channels included, remove global user declarations |
| `check-sat`, `check-sat-assuming` | Arbitrary Bool-term assumptions in 2.7; assumptions query-local; snapshot and result invalidation |
| `get-model`, `get-value` | Enabled model inspection of one fixed latest model, original symbols, exact and composite values |
| `get-assertions` | Enabled inspection of current original assertions, preserving `:named`/`:pattern` annotations |
| `get-assignment` | Enabled inspection of Boolean named terms, excluding numeric names; disabled query is an error |
| `get-proof`, `get-unsat-core`, `get-unsat-assumptions` | Optional capabilities; unsupported enabling options decline; disabled or wrong-mode queries do not fabricate evidence |
| `set-option`, `get-option` | Required options implemented; supported options validate types/modes; unsupported standard options retain/default on query; unknown options report unsupported |
| `set-info`, `get-info` | Script metadata distinct from solver information; required solver flags supported; unsupported optional flags explicit |
| `echo` | Return original string token, including enclosing quotes and doubled embedded quotes |
| `exit` | Print success only when enabled, stop processing, release owned output resources |

| Option | Standard requirement and default |
| --- | --- |
| `:print-success` | Required, false; change affects its own response |
| `:regular-output-channel` | Required, `"stdout"`; named file created/appended; change affects its own response |
| `:diagnostic-output-channel` | Required, `"stderr"`; named file created/appended |
| `:global-declarations` | Optional, false; start only |
| `:produce-assertions`, deprecated `:interactive-mode` | Optional, false; start only; aliases when implemented |
| `:produce-models` | Optional standard capability selected for this frontend, false; start only |
| `:produce-assignments` | Optional standard capability selected for this frontend, false; start only |
| `:produce-proofs`, `:produce-unsat-assumptions`, `:produce-unsat-cores` | Optional, false; start only; unsupported enables return unsupported without changing default |
| `:random-seed` | Optional, 0; start only |
| `:reproducible-resource-limit` | Optional, 0 disables; deterministic bounded checks when implemented |
| `:verbosity` | Optional, no standard default; diagnostics only |

Required solver information is `:authors`, `:error-behavior`, `:name` and
`:version`. Optional `:all-statistics` is query-mode only;
`:assertion-stack-levels` and `:reason-unknown` have their specified contextual
semantics. Continued execution after an error restores the exact previous state,
except diagnostic/timing information. Optional unsupported responses never imply
that a required operator or command has been implemented.

## Combined fixture identities

Solve fixtures use the public source-solving path. Model controls read
reconstructed values directly and evaluate the relevant table/store lookup
independently of the source lowerer; UNSAT fixtures assert a contradiction in an
interaction between families. Native representation controls construct their
exact authority explicitly, and rejection controls exercise publication gates.

| ID | Test name | Independent expectation |
| --- | --- | --- |
| C01 | `function bitvector results address reconstructed arrays` | SAT; the function maps the source value to `#b10`, whose array read is true |
| C02 | `array stores use interpreted bitvector equality for function indexes` | UNSAT; `#b01 + #b01` is the stored index `#b10` |
| C03 | `datatype function results retain bitvector and exact real array fields` | SAT; an explicit source constructor witness gives the actual function-result Packet `#b1` and weight `1/3` |
| C04 | `datatype array indexes preserve function congruence` | UNSAT; the selected constructor equals the stored constructor |
| C05 | `nested array models contain datatype values at bitvector indexes` | SAT; read `a[#b1][true]` is the green constructor |
| C06 | `finite datatype cardinality includes bitvector fields` | UNSAT; a one-bit record has exactly two values |
| C07 | `variable euclidean quotient converts into an array bitvector index` | SAT; `div(-7,3)=-3`, which becomes the two-bit index `#b01` |
| C08 | `exact rational array indexes agree through uninterpreted functions` | UNSAT; `1/3=2/6`, so both function applications address one array cell |
| C09 | `polymorphic distinct constants respect the boolean instance` | UNSAT; the Bool instance cannot contain three distinct values |
| C10 | `quoted source names survive composite model reconstruction` | SAT; quoted names and the original function interpretation are retained |
| C11 | `indexed divisibility composes with datatype bitvector array values` | SAT; a divisor index beyond machine precision remains an integer predicate beside composite terms |
| C12 | `mixed numeric conditionals retain real array index sorts` | SAT; the selected real branch remains `0.5` through every reduction |
| C13 | `qualified indexed operators retain their declared result sorts` | SAT; qualified datatype testers, bitvector extraction and integer divisibility preserve their ranks |
| C14 | `quoted builtin identities never turn quoted numeral names into literals` | SAT; quoted theory symbols resolve canonically while `\|1\|` remains a source variable |
| C15 | `quoted grammar word helpers retain lexical function meaning` | SAT; a function named `\|let\|` is an ordinary helper |
| C16 | `function model formals cannot shadow source constructors` | SAT; emitted model definitions remain well sorted with constructor `x0` |
| C17 | `retained polymorphic models instantiate new array and wide word ranks coherently` | SAT; new array and 129-bit ranks obey the already selected universal interpretation |
| C18 | `native exact array keys compare across different defining polynomials` | Native sqrt(2) keys agree extensionally despite distinct root syntax |
| C19 | `retained division queries preserve chosen total interpretations` | SAT; observed integer division/remainder-by-zero choices remain fixed, including congruent later inputs |
| C20 | `retained datatype selectors preserve observed and unobserved result sorts` | SAT; wrong-constructor selector choices remain fixed and correctly typed at each instantiation |
| C21 | `direct library named terms remain visible to retained model queries` | SAT; direct-library named definitions remain queryable |
| C22 | `rejected composite source checks withhold finite publications` | A source check which reads and rejects a reconstructed array suppresses all SAT publications |
| C23 | `sessions preserve composite models across query only assumptions` | Actual SAT/UNSAT/SAT session with fixed bitvector array reads and transient assumptions |
| C24 | `retained queries accept their previously returned abstract domain values` | Qualified abstract values remain valid inputs to queries on the same fixed model |
| C25 | `retained source constants take precedence over analytic extension names` | SAT; a source constant named `pi` retains its chosen value |
| C26 | `qualified numeric aliases retain real array index values` | SAT; qualified builtin results preserve alias-resolved Real sorts during composite reconstruction, including datatype exposure and polymorphic specialization |
| C27 | `retained polymorphic families construct deep array ranks without recursion` | SAT; a later rank contains 4096 array layers and a correctly typed scalar default |
| C28 | `retained deep rank construction observes its current witness allowance` | Later model-rank construction stops under its own current witness allowance |
| C29 | `mutual recursive definitions retain total interpretations at new arguments` | SAT; mutually defined parity functions retain their total meaning on later inputs |
| C30 | `mutual recursive equations refute inconsistent ground applications` | UNSAT; simultaneous recursive equations require both observed applications to agree |
| C31 | `assignment queries evaluate true and false named terms in the checked model` | Actual SAT/assignment transcript reports both selected truth values |
| C32 | `retained symbolic coordinates preserve native inverse identities` | A captured native exp(1) coordinate satisfies log(x)=1 and differs from its approximate placeholder zero without re-solving |
| C33 | `symbolic query syntax cannot replace unavailable native authority` | A matching printed symbolic term without certified authority declines arithmetic evaluation |
| C34 | `retained real division queries preserve their chosen total interpretation` | SAT; an observed real division-by-zero choice remains fixed in later queries |
| C35 | `defined numeric ranks retain real array index values` | SAT; declared Real argument/result ranks survive definition expansion in plain, datatype and polymorphic contexts |
| C36 | `retained defined real ranks select their fixed polymorphic interpretation` | SAT; definitions, conditionals, lets, matches and constructor fields retain Real ranks; Int/Real interpretation lookup is independent of instance order |
| C37 | `qualified unobserved function families retain their selected result rank` | SAT; an unused result-polymorphic family supports coherent later Real/Array ranks and rejects an invalid argument rank |
| C38 | `specialized helpers retain the observed ranks of captured polymorphic globals` | SAT; nested helper specialization reads the already fixed Real/Bool instances of a captured global |

C32 and C33 cover retained representations of the existing real-function
extensions. Their expected results use mathematical inverse/sign identities and
the native coordinate certificate.

The V fixtures exercise typed `get-value` evaluation in a retained empty model,
which still interprets every standard builtin. They complement source-lowering
and exhaustive circuit tests; they are not substitutes for them. Each listed
expression has an independently specified exact expected value. Comparison and
overflow groups include both true and false results.

| ID | Test name in `SmtQfValueConformanceTest` |
| --- | --- |
| V01 | `core queries preserve boolean associativity and polymorphic equality` |
| V02 | `integer queries retain exact arithmetic and euclidean signs` |
| V03 | `integer exponent queries retain zero and negative exponent conventions` |
| V04 | `real and mixed queries retain exact rational and floor values` |
| V05 | `bitvector logical queries preserve every specified bit` |
| V06 | `bitvector arithmetic queries preserve modular and signed results` |
| V07 | `bitvector wiring queries preserve widths and arbitrary rotation indexes` |
| V08 | `bitvector comparison queries distinguish signed and unsigned order` |
| V09 | `bitvector overflow queries compare mathematical results before truncation` |
| V10 | `bitvector conversion queries preserve signed and modular integer meanings` |

The R fixtures exercise legal deep source sorts without adding a semantic depth
bound. Typed capture, structural key identity, interruption and mutable public
argument-list behavior are observed separately.

| ID | Test name in `SmtQfResourceConformanceTest` |
| --- | --- |
| R01 | `deep array sorts retain source identities during typed capture` |
| R02 | `deep applied free sorts retain source identities during typed capture` |
| R03 | `deep structural interning observes the current remaining allowance` |
| R04 | `sort structural keys observe borrowed argument list changes` |
| R05 | `nested array cardinalities retain their capped finite meaning` |

The P fixtures isolate command protocol behavior. Their injected snapshot solver
is deliberately unused except in the explicit inconclusive-result lifecycle
control P03. Formula semantics and actual session solving remain covered by C23.

| ID | Test name in `SmtQfCommandConformanceTest` |
| --- | --- |
| P01 | `assertion inspection preserves original named and pattern annotations` |
| P02 | `echo responses preserve string delimiters and doubled quotes` |
| P03 | `unknown reasons belong only to the latest unchanged query context` |
| P04 | `disabled optional proof queries report errors after declined enablement` |
| P05 | `required error behavior and stack information survive an invalid logic change` |
| P06 | `option and information flags require keyword tokens` |

## Explicit scope boundaries

The first-order input scope does not include `forall`/`exists`, floating-point
operators/sorts, String/RegLan, or higher-order `->`, `lambda` and `@` application.
String tokens remain required for commands and attributes. The nonstandard
constant-array input operator `(as const (Array ...))` is not an ArraysEx input
requirement; constant/default-store array terms in model output are a separate
representation convention. Existing optimization and transcendental extensions
have their own tests and are not counted as standard SMT-LIB coverage.
