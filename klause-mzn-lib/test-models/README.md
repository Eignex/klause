# MiniZinc diagnostic models

These repository-authored smoke and diagnostic models use the repository's
Apache-2.0 license. The `mzn-smoke` catalog keeps stable problem names independent
of the model filenames.

Every model filename starts with `klause-test-`. MiniZinc searches the model's
directory for includes, including transitive includes from `globals.mzn`; diagnostic
models must not share standard-library filenames. Keep standard-library includes
unchanged and preserve source bytes when renaming a fixture.
