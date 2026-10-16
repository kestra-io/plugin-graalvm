package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.graalvm.AllowedPathsConfiguration;
import jakarta.inject.Inject;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.kestra.plugin.graalvm.AllowedPathsConfiguration.DATA_DIR;
import static io.kestra.plugin.graalvm.AllowedPathsConfiguration.OUTSIDE_FILE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest(environments = AllowedPathsConfiguration.ENVIRONMENT)
class AllowedPathsTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void readsAllowedPath() throws Exception {
        var task = eval("""
            import os
            with open('%s') as f:
                line_count = sum(1 for _ in f)
            entries = ','.join(sorted(os.listdir('%s')))
            """.formatted(DATA_DIR.resolve("events.csv"), DATA_DIR), "line_count", "entries");

        var outputs = task.run(runContext(task)).getOutputs();

        assertThat(outputs.get("line_count"), is(3));
        assertThat(outputs.get("entries"), is("events.csv,lib.js,lib.rb"));
    }

    @Test
    void cannotWriteAllowedPath() {
        for (var script : List.of(
            "open('%s', 'w').write('x')".formatted(DATA_DIR.resolve("events.csv")),
            "open('%s', 'a').write('x')".formatted(DATA_DIR.resolve("events.csv")),
            "open('%s', 'w').write('x')".formatted(DATA_DIR.resolve("new.csv")),
            "import os\nos.remove('%s')".formatted(DATA_DIR.resolve("events.csv")),
            "import os\nos.mkdir('%s')".formatted(DATA_DIR.resolve("dir"))
        )) {
            var task = eval(script);
            var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)), script);
            assertThat(script, exception.getMessage(), startsWith("PermissionError"));
        }
        assertThat(readUnchecked(DATA_DIR.resolve("events.csv")), is("id\n1\n2\n"));
        assertThat(Files.exists(DATA_DIR.resolve("new.csv")), is(false));
        assertThat(Files.exists(DATA_DIR.resolve("dir")), is(false));
    }

    @Test
    void reportsAllowedPathAsReadOnly() throws Exception {
        var task = eval("""
            import os
            readable = os.access('%1$s', os.R_OK)
            writable = os.access('%1$s', os.W_OK)
            """.formatted(DATA_DIR.resolve("events.csv")), "readable", "writable");

        var outputs = task.run(runContext(task)).getOutputs();

        assertThat(outputs.get("readable"), is(true));
        assertThat(outputs.get("writable"), is(false));
    }

    @Test
    void stillDeniesOtherPaths() {
        // the sibling's name starts with the allowed directory's name, also reached by going back up from it
        for (var path : List.of(OUTSIDE_FILE, DATA_DIR.resolve("..").resolve(OUTSIDE_FILE.getFileName()))) {
            var task = eval("open('%s').read()".formatted(path));

            var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)), path.toString());

            // GraalPy reports only the errno, the full denial message is checked in WorkingDirFileSystemAllowedPathsTest
            assertThat(path.toString(), exception.getMessage(), startsWith("PermissionError"));
        }
    }

    @Test
    void configurationOnlyAppliesToTheConfiguredTaskType() {
        // FileTransform has no allowed-paths, even though Eval does
        var task = FileTransform.builder()
            .id("unit-test")
            .type(FileTransform.class.getName())
            .from(Property.ofValue("{\"id\": 1}"))
            .script(Property.ofValue("open('%s').read()".formatted(DATA_DIR.resolve("events.csv"))))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)));

        assertThat(exception.getMessage(), startsWith("PermissionError"));
    }

    @Test
    void cannotReadThePluginConfiguration() {
        for (var script : List.of("runContext.pluginConfiguration('allowed-paths')", "runContext.pluginConfigurations()")) {
            var task = eval(script);

            var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)), script);

            // refused by RunContextProxy, not a script error such as an unbound runContext
            assertThat(script, exception.isHostException(), is(true));
            assertThat(script, exception.asHostException(), instanceOf(UnsupportedOperationException.class));
        }
    }

    private RunContext runContext(Task task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    private static Eval eval(String script, String... outputs) {
        return Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(script))
            .outputs(Property.ofValue(List.of(outputs)))
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
