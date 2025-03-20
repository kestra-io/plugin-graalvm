package io.kestra.plugin.graalvm.ruby;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.startsWith;

@KestraTest
class EvalTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.of(
                """
                      Counter = Java.type('io.kestra.core.models.executions.metrics.Counter')
                      FileOutputStream = Java.type('java.io.FileOutputStream')
                      # all variables must be imported before use
                      logger = Polyglot.import('logger')
                      runContext = Polyglot.import('runContext')
                      logger.info('Task started')
                      runContext.metric(Counter.of('total', 666, 'name', 'bla'))
                      map = {test: 'here'}
                      tempFile = runContext.workingDir().createTempFile().toFile()
                      output = FileOutputStream.new(tempFile)
                      output.write(256)
                      out = runContext.storage().putFile(tempFile)
                      return {map: map, out: out}
                    """
            ))
            .outputs(Property.of(List.of("map", "out")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getResult(), nullValue());
        assertThat(runOutput.getOutputs(), aMapWithSize(2));
        assertThat((Map<String, Object>) runOutput.getOutputs().get("map"), aMapWithSize(1));
        assertThat(((Map<String, Object>) runOutput.getOutputs().get("map")).get("test"), is("here"));
        assertThat(((URI) runOutput.getOutputs().get("out")).toString(), startsWith("kestra:///"));
    }
}