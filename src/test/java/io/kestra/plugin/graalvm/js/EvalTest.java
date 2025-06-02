package io.kestra.plugin.graalvm.js;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
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
                var BigDecimal = Java.type('java.math.BigDecimal');
                BigDecimal.valueOf(10).pow(20)"""
            ))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getOutputs(), nullValue());
        assertThat(runOutput.getResult(), is(new BigDecimal("100000000000000000000")));
    }

    @Test
    void runMember() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.ofValue(
                "({ id   : 42, text : '42', arr  : [1,42,3] })"
            ))
            .outputs(Property.ofValue(List.of("id", "text")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getResult(), nullValue());
        assertThat(runOutput.getOutputs(), aMapWithSize(2));
        assertThat(runOutput.getOutputs().get("id"), is(42));
        assertThat(runOutput.getOutputs().get("text"), is("42"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(
                """
                    (function() {
                    var Counter = Java.type('io.kestra.core.models.executions.metrics.Counter');
                    var File = Java.type('java.io.File');
                    var FileOutputStream = Java.type('java.io.FileOutputStream');

                    runContext.metric(Counter.of('total', 666, 'name', 'bla'));

                    map = {'test': 'here'};
                    var tempFile = runContext.workingDir().createTempFile().toFile();
                    var output = new FileOutputStream(tempFile);
                    output.write(256);

                    out = runContext.storage().putFile(tempFile);
                    return {"map": map, "out": out};
                    })"""
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
}