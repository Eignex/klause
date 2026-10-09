# Source residual validation, session d929

Issue #1923 concerns source scans in `RevisedSimplex.optimal`. They compute primal,
bound and basic-dual diagnostics. For an unscaled optimum these values do not change
the candidate or its certification. Scaled scans also reject nonfinite arithmetic;
that guard remains, along with scans on truncated iterates and direct float solves.
Certified callers defer only the unscaled optimum diagnostics. Tolerance acceptance,
source callbacks, exact basis verification and their decline policies are unchanged.

## Measurements

[Baseline job 813](http://192.168.50.104:8420/?job=813) measured scans before deferral at
`c9537351a`. [Paired job 815](http://192.168.50.104:8420/?job=815) compared that commit
with `f60564698`. Both selected `suite=miplib3 name=egout,flugpl max=2 seed=1 reference=any`,
with `engine=cp processors=1 lp=default timeout=20000 exact=false`, solver seed 1,
three repeats and parallel 1 on the lab host. The lab queue serialized the cases;
each arm rebuilt its CLI with `:klause-cli:installJvmDist`. Paired arm order alternated.
Specs, raw files, case JSON and independent checks are preserved in
`/home/rasmus/Workspaces/lp-evidence/session-lp-d929/` and the lab job Files pages.

| Instance | Baseline scans per repeat | Baseline scan milliseconds | Paired median solve seconds, scans / deferred |
| --- | --- | --- | --- |
| egout | 364–397 | 1.624–1.767 | 19.908 / 19.909 |
| flugpl | 418 | 2.088–2.315 | 0.636 / 0.631 |

Every recorded scan in both experiments was scaled. Deferral therefore removed no
scan from these cases, and these timings establish no optimization speedup. Egout
returned a feasible, unproven 606.0797 in every repeat; its budget-limited runs do
not establish runtime equivalence. Flugpl reported optimum 1201500 every time.
All 12 paired witnesses passed the independent source MPS checker from
`lp-evidence/session-stage-c-exact-duals/check_witness.py` at relative tolerance 1e-7,
including rows, bounds and integer markers. This does not independently prove optimality.

The completed flugpl runs have identical per-certifier declines in every arm and
repeat: 351 integer-certifier declines, 211 safe-objective-bound declines and zero
exact-basis, exact-Farkas, exact-point or rational-outcome declines. Each arm supplied
46 accepted float optima per repeat. Egout's raw attempt and decline counts vary
with the amount of search completed before its deadline; those totals cannot be
used to infer a changed certifier refusal rate. No acceptance or decline rule changed.

An additional pre-deferral workstation diagnostic on 80bau3b, rebuilt at `c9537351a`,
used `-e cp -p 1 --lp default -r 1 -t 20000 -s`. It recorded two component scans,
one scaled and one unscaled, totaling 3.227 ms within a 3.827 s solve. It reported
optimum 987224.1924090875. This single concurrent-workstation observation describes
coverage and is excluded from lab performance comparisons; the source input and
its SHA-256 are recorded in [the baseline report](lp-fallback-d929.md).

The measurements support a narrow removal of redundant diagnostics, not a broad
LP speedup claim. Targeted regressions cover omitted scans with an attained exact
optimum, retained direct diagnostics, scaled validation and merged component timing.
The full local `check lintDocs` gate was skipped; GitHub CI supplies the full gate.
