import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.graalvm.python.Eval;
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
            .script(
                """
                import java.math.BigDecimal as BigDecimal
                BigDecimal.valueOf(10).pow(20)"""
            )
            .build();

        var runOutput = task.run(runContext);
        System.out.println(runOutput);
    }

    @Test
    void runMember() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(
                """
                import json
                json.dumps({ 'id'   : 42, 'text' : '42', 'arr'  : [1,42,3] })"""
            )
            .outputs(List.of("id", "text"))
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
            .script(
                """
                    import java
                    import java.io.File as File
                    import java.io.FileOutputStream as FileOutputStream

                    # types other than one coming from the Java SDK must be defined this way
                    Counter = java.type("io.kestra.core.models.executions.metrics.Counter")

                    runContext.metric(Counter.of('total', 666, 'name', 'bla'))

                    map = {'test': 'here'}
                    tempFile = runContext.workingDir().createTempFile().toFile()
                    output = FileOutputStream(tempFile)
                    output.write(256)

                    out = runContext.storage().putFile(tempFile)
                    {"map": map, "out": out}
                    """
            )
            .outputs(List.of("map", "out"))
            .build();

        var runOutput = task.run(runContext);
        System.out.println(runOutput);
    }
}