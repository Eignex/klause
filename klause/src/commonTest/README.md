# Solver test startup

Tests that exercise solver or numerical startup inherit `RuntimeInitializedTest`.
On the JVM, its inherited JUnit `BeforeClass` hook initializes the shared runtime
once per test process, before individual test timing. It also runs when Gradle
selects a single class or method. Native tests use an empty base class.

`SolverTestRuntime` uses fixed in-memory arithmetic, schema and search inputs.
Initialization failures fail class setup; behavior assertions stay in the tests.
Keep setup bounded and offline, and do not move expensive behavior fixtures into it.
