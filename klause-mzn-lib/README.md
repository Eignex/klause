# klause-mzn-lib

MiniZinc solver library and CLI integration for klause.

## What this contains

- `share/minizinc/solvers/klause.msc` — solver configuration MiniZinc consumes.
- `share/minizinc/klause/redefinitions.mzn` — declares the FlatZinc predicates
  klause supports natively. Anything not listed falls back to MiniZinc's
  standard library, which decomposes globals (cumulative, regular, circuit,
  table, element, lex, gcc, nvalue, ...) into combinations of the
  predicates we do support.
- `bin/klause-fzn` — wrapper script MiniZinc invokes. Delegates to the
  installed gradle distribution of `klause-cli`.
- `test-models/` — small MiniZinc smoke tests.

## Setup

1. Build the CLI distribution from the repo root:

   ```
   ./gradlew :klause-cli:installDist
   ```

   This produces `klause-cli/build/install/klause-cli-jvm/bin/klause-cli`.
   The wrapper at `klause-mzn-lib/bin/klause-fzn` finds it via `$KLAUSE_HOME`
   (defaults to two directories above the wrapper script).

2. Point MiniZinc at the solver config. Either pass it per-invocation:

   ```
   minizinc --solver /abs/path/to/klause-mzn-lib/share/minizinc/solvers/klause.msc model.mzn
   ```

   or copy/symlink the .msc into `~/.minizinc/solvers/` and the library into
   `~/.minizinc/share/minizinc/klause/`, after which `--solver com.eignex.klause`
   resolves it by id.

3. Smoke test:

   ```
   minizinc --solver com.eignex.klause test-models/queens.mzn -a -n 10
   ```

   Should print 10 distinct queens solutions terminated by `==========`.

## Native predicates we claim

See `share/minizinc/klause/redefinitions.mzn` for the complete list. Today:
basic Boolean (`bool_clause`, `array_bool_and/or`), int/bool/float linear
(reified and non-reified), `int_times`, `all_different_int`, `count_eq`.

Everything else MiniZinc decomposes via its standard library.
