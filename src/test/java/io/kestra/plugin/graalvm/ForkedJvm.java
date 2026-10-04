package io.kestra.plugin.graalvm;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

// Runs a main class in a fresh JVM, for tests that need the shared GraalVM engine created from scratch.
public final class ForkedJvm {
    private ForkedJvm() {
    }

    public static void run(List<String> jvmArgs, Class<?> mainClass, String successMarker) throws Exception {
        run(jvmArgs, mainClass, successMarker, List.of(), pid -> { });
    }

    public static void run(
        List<String> jvmArgs,
        Class<?> mainClass,
        String successMarker,
        List<String> programArgs,
        ThrowingLongConsumer onPidAvailable
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());
        command.addAll(programArgs);

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().remove("XDG_CACHE_HOME");
        Process process = processBuilder.start();
        onPidAvailable.accept(process.pid());

        var output = new StringBuilder();
        Thread outputReader = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(line -> output.append(line).append('\n'));
            } catch (IOException ignored) {
                // stream closes when the process is destroyed below; nothing to recover
            }
        });

        boolean completed = process.waitFor(90, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        outputReader.join(java.time.Duration.ofSeconds(5).toMillis());

        assertThat("forked JVM did not complete in time, output so far:\n" + output, completed, is(true));
        assertThat(output.toString(), containsString(successMarker));
        assertThat(process.exitValue(), is(0));
    }

    @FunctionalInterface
    public interface ThrowingLongConsumer {
        void accept(long value) throws IOException;
    }
}
