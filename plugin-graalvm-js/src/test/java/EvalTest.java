import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.graalvm.js.Eval;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

@MicronautTest
class EvalTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void runValue() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.of(
                """
                var BigDecimal = Java.type('java.math.BigDecimal');
                BigDecimal.valueOf(10).pow(20)"""
            ))
            .build();

        var runOutput = task.run(runContext);
        System.out.println(runOutput);
    }

    @Test
    void runMember() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.of(
                "({ id   : 42, text : '42', arr  : [1,42,3] })"
            ))
            .outputs(Property.of(List.of("id", "text")))
            .build();

        var runOutput = task.run(runContext);
        System.out.println(runOutput);
    }

    @Test
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.of(
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
            .outputs(Property.of(List.of("map", "out")))
            .build();

        var runOutput = task.run(runContext);
        System.out.println(runOutput);
    }
}