package com.eignex.klause.bench.profile;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class OriginalMatchingFixtureAllocation {
    private static String sha(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void originalClass(String name, Path jar) throws Exception {
        Class<?> target = Class.forName(name);
        Path location = Path.of(target.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!Files.isSameFile(location, jar)) throw new IllegalStateException("Original CLI class origin differs: " + name);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = target.getResourceAsStream(target.getSimpleName() + ".class")) {
            if (input == null) throw new IllegalStateException("Original class resource is missing: " + name);
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        System.out.println("CLASS\t" + name + "\t" + location + "\t" + HexFormat.of().formatHex(digest.digest()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[1].matches("[0-9a-f]{64}") || !args[4].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Original jar, fixture output, both full SHAs and fixture required");
        Path jar = Path.of(args[0]).toRealPath();
        if (!Files.isRegularFile(jar) || !sha(jar).equals(args[1])) throw new IllegalStateException("Original CLI jar bytes differ");
        String methodName = switch (args[2]) {
            case "matching" -> "alldifferent matching deductions should be implied by their reasons under carved holes";
            case "except-zero" -> "alldifferent except zero deductions should be implied by their reasons under carved holes";
            default -> throw new IllegalArgumentException("Unknown original fixture");
        };
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (arguments.stream().anyMatch(value -> value.startsWith("-javaagent:") || value.startsWith("-agentlib:") || value.startsWith("-agentpath:"))) {
            throw new IllegalStateException("Agent-modified fixture runs are not admitted");
        }
        originalClass("com.eignex.klause.factor.global.internals.ReginMatcherKt", jar);
        originalClass("com.eignex.klause.factor.global.internals.ReginIncrementalKt", jar);
        Class<?> fixture = Class.forName("com.eignex.klause.factor.global.AllDifferentPropagatorTest");
        Path fixtureOrigin = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!Files.isSameFile(fixtureOrigin, Path.of(args[3]).toRealPath())) {
            throw new IllegalStateException("Original compiled fixture origin differs");
        }
        Path fixtureClass = fixtureOrigin.resolve("com/eignex/klause/factor/global/AllDifferentPropagatorTest.class");
        if (!sha(fixtureClass).equals(args[4])) throw new IllegalStateException("Original compiled fixture bytes differ");
        System.out.println("FIXTURE_CLASS\t" + fixtureOrigin + "\t" + args[4]);
        Object instance = fixture.getConstructor().newInstance();
        Method method = fixture.getMethod(methodName);
        System.out.println("FIXTURE\t" + fixture.getName() + "\t" + methodName);
        System.out.println("JAR\t" + jar + "\t" + args[1]);
        System.out.println("VM\t" + System.getProperty("java.runtime.version") + "\t" + arguments);
        if (Runtime.getRuntime().availableProcessors() != 1 || !arguments.contains("-Xmx3g") ||
                !arguments.contains("-XX:+UseSerialGC") || !arguments.contains("-XX:ActiveProcessorCount=1")) {
            throw new IllegalStateException("Pinned fixture runtime controls required");
        }
        System.out.println("PID\t" + ProcessHandle.current().pid());
        System.out.println("CPU\t" + Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("Cpus_allowed_list:")).findFirst().orElseThrow());
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocation.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters required");
        allocation.setThreadAllocatedMemoryEnabled(true);
        if (!allocation.isThreadAllocatedMemoryEnabled()) throw new IllegalStateException("Allocation counters unavailable");
        for (int round = 0; round < 30; round++) method.invoke(instance);
        long tid = Thread.currentThread().threadId();
        for (int round = 0; round < 21; round++) {
            long before = allocation.getThreadAllocatedBytes(tid);
            long start = System.nanoTime();
            method.invoke(instance);
            long elapsed = System.nanoTime() - start;
            long after = allocation.getThreadAllocatedBytes(tid);
            if (before < 0 || after < before) throw new IllegalStateException("Allocation counter declined");
            System.out.println("MEASURE\t" + round + "\t" + elapsed + "\t" + (after - before));
        }
        if (!sha(jar).equals(args[1])) throw new IllegalStateException("Original CLI jar changed during fixture execution");
        if (!sha(fixtureClass).equals(args[4])) throw new IllegalStateException("Original compiled fixture changed");
        System.out.println("COMPLETED\t" + args[2]);
    }
}
