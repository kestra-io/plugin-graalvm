package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class EvalTest {
    @Inject
    private RunContextFactory runContextFactory;

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
}