package io.kestra.plugin.graalvm;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.plugins.PluginConfiguration;
import io.kestra.core.plugins.PluginConfigurations;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Goes through the real plugin configuration lookup, so the allowed paths only apply to the configured task types.
@KestraTest
@Property(name = AllowedPathsTest.ENABLED, value = "true")
class AllowedPathsTest {
    static final String ENABLED = "kestra.graalvm.test.allowed-paths";

    // static: the plugin configuration bean is created before the tests run
    private static final Path DATA_DIR = createDataDir();
    private static final Path OUTSIDE_FILE = DATA_DIR.resolveSibling(DATA_DIR.getFileName() + "-secret.txt");

    @Inject
    private RunContextFactory runContextFactory;

    // only enabled for this test class, so the other tests keep running without plugin configuration
    @Factory
    @Requires(property = ENABLED)
    static class AllowedPathsConfiguration {
        @Singleton
        @Replaces(PluginConfigurations.class)
        PluginConfigurations pluginConfigurations() {
            var values = Map.<String, Object>of(WorkingDirFileSystem.ALLOWED_PATHS, List.of(DATA_DIR.toString()));
            return new PluginConfigurations(List.of(
                new PluginConfiguration(0, io.kestra.plugin.graalvm.python.Eval.class.getName(), values),
                new PluginConfiguration(1, io.kestra.plugin.graalvm.js.Eval.class.getName(), values)
            ));
        }
    }

    private static Path createDataDir() {
        try {
            var dir = Files.createTempDirectory("kestra-graalvm-allowed-paths");
            Files.writeString(dir.resolve("events.csv"), "id\n1\n2\n");
            Files.writeString(dir.resolve("lib.js"), "var fromLib = 'loaded';");
            Files.writeString(dir.resolveSibling(dir.getFileName() + "-secret.txt"), "secret");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void pythonReadsAllowedPath() throws Exception {
        var task = python("""
            import os
            with open('%s') as f:
                line_count = sum(1 for _ in f)
            entries = ','.join(sorted(os.listdir('%s')))
            """.formatted(DATA_DIR.resolve("events.csv"), DATA_DIR), "line_count", "entries");

        var outputs = task.run(runContext(task)).getOutputs();

        assertThat(outputs.get("line_count"), is(3));
        assertThat(outputs.get("entries"), is("events.csv,lib.js"));
    }

    @Test
    void pythonCannotWriteAllowedPath() {
        for (var script : List.of(
            "open('%s', 'w').write('x')".formatted(DATA_DIR.resolve("events.csv")),
            "open('%s', 'a').write('x')".formatted(DATA_DIR.resolve("events.csv")),
            "open('%s', 'w').write('x')".formatted(DATA_DIR.resolve("new.csv")),
            "import os\nos.remove('%s')".formatted(DATA_DIR.resolve("events.csv")),
            "import os\nos.mkdir('%s')".formatted(DATA_DIR.resolve("dir"))
        )) {
            var task = python(script);
            var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)), script);
            assertThat(script, exception.getMessage(), startsWith("PermissionError"));
        }
        assertThat(readUnchecked(DATA_DIR.resolve("events.csv")), is("id\n1\n2\n"));
        assertThat(Files.exists(DATA_DIR.resolve("new.csv")), is(false));
        assertThat(Files.exists(DATA_DIR.resolve("dir")), is(false));
    }

    @Test
    void pythonReportsAllowedPathAsReadOnly() throws Exception {
        var task = python("""
            import os
            readable = os.access('%1$s', os.R_OK)
            writable = os.access('%1$s', os.W_OK)
            """.formatted(DATA_DIR.resolve("events.csv")), "readable", "writable");

        var outputs = task.run(runContext(task)).getOutputs();

        assertThat(outputs.get("readable"), is(true));
        assertThat(outputs.get("writable"), is(false));
    }

    @Test
    void pythonStillDeniesOtherPaths() {
        var task = python("open('%s').read()".formatted(OUTSIDE_FILE));

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)));

        // GraalPy reports only the errno, the full denial message is checked in WorkingDirFileSystemAllowedPathsTest
        assertThat(exception.getMessage(), startsWith("PermissionError"));
    }

    @Test
    void javascriptLoadsFromAllowedPath() throws Exception {
        var task = io.kestra.plugin.graalvm.js.Eval.builder()
            .id("unit-test")
            .type(io.kestra.plugin.graalvm.js.Eval.class.getName())
            .script(io.kestra.core.models.property.Property.ofValue("load('%s'); ({result: fromLib})".formatted(DATA_DIR.resolve("lib.js"))))
            .outputs(io.kestra.core.models.property.Property.ofValue(List.of("result")))
            .build();

        assertThat(task.run(runContext(task)).getOutputs().get("result"), is("loaded"));
    }

    @Test
    void configurationOnlyAppliesToTheConfiguredTaskType() {
        // python.FileTransform has no allowed-paths, even though python.Eval does
        var task = io.kestra.plugin.graalvm.python.FileTransform.builder()
            .id("unit-test")
            .type(io.kestra.plugin.graalvm.python.FileTransform.class.getName())
            .from(io.kestra.core.models.property.Property.ofValue("{\"id\": 1}"))
            .script(io.kestra.core.models.property.Property.ofValue("open('%s').read()".formatted(DATA_DIR.resolve("events.csv"))))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)));

        assertThat(exception.getMessage(), startsWith("PermissionError"));
    }

    @Test
    void scriptsCannotReadThePluginConfiguration() {
        var task = python("runContext.pluginConfiguration('allowed-paths')");

        assertThrows(PolyglotException.class, () -> task.run(runContext(task)));
    }

    private RunContext runContext(Task task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    private static io.kestra.plugin.graalvm.python.Eval python(String script, String... outputs) {
        return io.kestra.plugin.graalvm.python.Eval.builder()
            .id("unit-test")
            .type(io.kestra.plugin.graalvm.python.Eval.class.getName())
            .script(io.kestra.core.models.property.Property.ofValue(script))
            .outputs(io.kestra.core.models.property.Property.ofValue(List.of(outputs)))
            .build();
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
