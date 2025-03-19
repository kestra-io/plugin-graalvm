package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.graalvm.AbstractEval;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute a nashorn (javascript) script."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                    id: evalPython
                    namespace: company.team

                    tasks:
                      - id: evalPython
                        type: io.kestra.plugin.graalvm.python.Eval
                        outputs:
                          - out
                          - map
                        script: |
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
                            {"map": map, "out": out}"""
        )
    },
    beta = true
)
public class Eval extends AbstractEval {
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "python");
    }
}
