package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EvalTest {
    // The issue's own repro script: these modules extract native/platform resources from the Python
    // stdlib and used to fail once the GraalPy user resource cache (~/.cache/org.graalvm.polyglot)
    // was unwritable, wiped, or corrupted (kestra-io/plugin-graalvm#40).
    static final String STDLIB_IMPORT_SCRIPT = """
        import json
        import ssl
        import urllib
        import http
        import email
        import sqlite3
        import lzma
        import zoneinfo
        """;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void rejectModulePathTraversal() {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(
                Map.of("../evil.py", "x = 1\n")
            ))
            .script(Property.ofValue("pass\n"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("path separators"));
    }

    @Test
    void runValue() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.ofValue(
                """
                import java.math.BigDecimal as BigDecimal
                BigDecimal.valueOf(10).pow(20)"""
            ))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getOutputs(), nullValue());
        assertThat(runOutput.getResult(), is(new BigDecimal("100000000000000000000")));
    }

    @Test
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(
                """
                    import java
                    import java.io.File as File
                    import java.io.FileOutputStream as FileOutputStream
                    # types other than one coming from the Java SDK must be defined this way
                    Counter = java.type("io.kestra.core.models.executions.metrics.Counter")
                    logger.info('Task started')
                    runContext.metric(Counter.of('total', 666, 'name', 'bla'))
                    map = {'test': 'here'}
                    tempFile = runContext.workingDir().createTempFile().toFile()
                    output = FileOutputStream(tempFile)
                    output.write(256)
                    out = runContext.storage().putFile(tempFile)
                    """
            ))
            .outputs(Property.ofValue(List.of("map", "out")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getResult(), nullValue());
        assertThat(runOutput.getOutputs(), aMapWithSize(2));
        assertThat((Map<String, Object>) runOutput.getOutputs().get("map"), aMapWithSize(1));
        assertThat(((Map<String, Object>) runOutput.getOutputs().get("map")).get("test"), is("here"));
        assertThat(((URI) runOutput.getOutputs().get("out")).toString(), startsWith("kestra:///"));
    }

    @Test
    void runFunctionWithModule() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(
                Map.of("hello.py", """
                    def hello(name):
                      print("Hello " + name)
                    """)
            ))
            .script(Property.ofValue(
                """
                    import hello
                    hello.hello("Loïc")
                    """
            ))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
    }

    @Test
    void stdlibImports() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(STDLIB_IMPORT_SCRIPT))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
    }

    @Test
    void stdlibImportsWithUnwritableDefaultResourceCache(@TempDir Path tempDir) throws Exception {
        // Reproduces the issue: with neither polyglot.engine.userResourceCache nor
        // polyglot.engine.resourcePath set, GraalVM falls back to $XDG_CACHE_HOME or
        // ${user.home}/.cache/org.graalvm.polyglot to extract the stdlib. Pointing user.home at a
        // regular file (not a directory) makes that OS-default location unusable regardless of OS
        // user/permissions (including root in CI). EngineHolder must default userResourceCache to a
        // writable path under java.io.tmpdir before that broken default is ever consulted.
        Path unwritableHome = tempDir.resolve("not-a-directory");
        Files.createFile(unwritableHome);

        runForked(
            List.of("-Duser.home=" + unwritableHome),
            StdlibImportForkMain.class,
            StdlibImportForkMain.SUCCESS_MARKER
        );
    }

    @Test
    void engineHolderFallsBackWhenResourceCacheDirIsUnwritable(@TempDir Path tempDir) throws Exception {
        // Forces Files.createDirectories(cacheDir) to fail inside EngineHolder.createEngine()'s static
        // field initializer by pre-creating "<tmpdir>/kestra-graalvm-resource-cache" as a regular file
        // instead of a directory, so that exact subdirectory can never be created. java.io.tmpdir itself
        // stays a real, writable directory so unrelated JVM startup machinery (e.g. Flight Recorder) is
        // unaffected. The forked main runs two scripts back to back: if the caught IOException were
        // rethrown instead of logged and swallowed, the static holder would be poisoned for the rest of
        // the JVM's lifetime (JLS class-initialization semantics), and even the second script would fail
        // with NoClassDefFoundError instead of the original exception.
        Files.createFile(tempDir.resolve("kestra-graalvm-resource-cache"));

        runForked(
            List.of("-Djava.io.tmpdir=" + tempDir),
            EngineHolderFallbackForkMain.class,
            EngineHolderFallbackForkMain.SUCCESS_MARKER
        );
    }

    private void runForked(List<String> jvmArgs, Class<?> mainClass, String successMarker) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().remove("XDG_CACHE_HOME");
        Process process = processBuilder.start();

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

    @Test
    void rejectsSandboxWeakeningOption() {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("pass\n"))
            .options(Property.ofValue(Map.of("python.PosixModuleBackend", "native")))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("python.PosixModuleBackend"));
    }

    @Test
    void blocksDirectNativeFFIFromPythonScripts() {
        // Demonstrates the boundary PythonNativeAccessGuard enforces: allowNativeAccess(true) is
        // required engine-wide for C-extension-backed stdlib modules (ssl, sqlite3, lzma -- see
        // stdlibImports() above), but a script must not be able to use that grant to shell out directly
        // via ctypes, e.g. ctypes.CDLL(None).system("..."). Importing ctypes must fail even though
        // native access is otherwise enabled for this task.
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("import ctypes\n"))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("ctypes"));
    }
}