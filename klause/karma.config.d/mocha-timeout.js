// Browser tests run under Karma, where Mocha's 2s default timeout applies to each test;
// the `useMocha { timeout = "120s" }` in build.gradle.kts only reaches the nodejs test
// tasks. Without this override, compute-heavy solver tests flake on loaded CI runners
// with "Timeout of 2000ms exceeded" (seen on wasmJsBrowserTest). Keep in sync with the
// nodejs budget in build.gradle.kts. Mutates the nested key instead of config.set so the
// client.args the Kotlin plugin uses for test filtering survive.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 120000 });
