# Exact global wrapper fixtures

Small internally authored MiniZinc models exercise solver-library decompositions and
index normalization. Each subject accepts `x = 1` and rejects `x = 0`; removing the
subject permits zero. The shared driver selects finite-only, linear-real or
polynomial-real coupling and a pinned witness, a refutation or minimization.
Polynomial variants minimize their real square-root coordinate; the other variants
minimize the integer argument's exact real image.
Expected minima are one. These fixtures use the repository's licence.
