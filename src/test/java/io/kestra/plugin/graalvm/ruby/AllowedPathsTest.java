package io.kestra.plugin.graalvm.ruby;

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

import java.util.List;
import java.util.Map;

import static io.kestra.plugin.graalvm.AllowedPathsConfiguration.DATA_DIR;
import static io.kestra.plugin.graalvm.AllowedPathsConfiguration.OUTSIDE_FILE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest(environments = AllowedPathsConfiguration.ENVIRONMENT)
class AllowedPathsTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void loadsFromAllowedPath() throws Exception {
        for (var statement : List.of("load", "require")) {
            var task = eval("""
                %s '%s'
                return {result: $from_lib}
                """.formatted(statement, DATA_DIR.resolve("lib.rb")), "result");

            assertThat(statement, task.run(runContext(task)).getOutputs().get("result"), is("loaded"));
        }
    }

    @Test
    void stillDeniesOtherPaths() {
        var task = eval("load '%s'".formatted(OUTSIDE_FILE));

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)));

        assertThat(exception.getMessage(), containsString("is denied"));
    }

    @Test
    void fileApisStayUnavailableInAllowedPath() {
        // Ruby File/Dir need native access, which is not granted
        var task = eval("File.read('%s')".formatted(DATA_DIR.resolve("events.csv")));

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)));

        assertThat(exception.getMessage(), is("native access is not allowed"));
    }

    @Test
    void cannotReadThePluginConfiguration() {
        for (var call : List.of("pluginConfiguration('allowed-paths')", "pluginConfigurations()")) {
            var task = eval("Polyglot.import('runContext')." + call);

            var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)), call);

            // refused by RunContextProxy, not a script error such as a failed import
            assertThat(call, exception.isHostException(), is(true));
            assertThat(call, exception.asHostException(), instanceOf(UnsupportedOperationException.class));
        }
    }

    @Test
    void readsAllowedPathThroughAnotherLanguage() throws Exception {
        // the allowed path is open to every language of the context, not only to Ruby's load and require
        var task = eval("return {result: Polyglot.eval('python', \"open('%s').read()\")}".formatted(DATA_DIR.resolve("events.csv")), "result");

        assertThat(task.run(runContext(task)).getOutputs().get("result"), is("id\n1\n2\n"));

        var denied = eval("Polyglot.eval('python', \"open('%s').read()\")".formatted(OUTSIDE_FILE));
        var exception = assertThrows(PolyglotException.class, () -> denied.run(runContext(denied)));
        assertThat(exception.getMessage(), startsWith("PermissionError"));
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
}
