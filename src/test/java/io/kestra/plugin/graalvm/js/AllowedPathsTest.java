package io.kestra.plugin.graalvm.js;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest(environments = AllowedPathsConfiguration.ENVIRONMENT)
class AllowedPathsTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void loadsFromAllowedPath() throws Exception {
        var task = eval("load('%s'); ({result: fromLib})".formatted(DATA_DIR.resolve("lib.js")), "result");

        assertThat(task.run(runContext(task)).getOutputs().get("result"), is("loaded"));
    }

    @Test
    void stillDeniesOtherPaths() {
        var task = eval("load('%s')".formatted(OUTSIDE_FILE));

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext(task)));

        assertThat(exception.getMessage(), containsString("is denied"));
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
}
