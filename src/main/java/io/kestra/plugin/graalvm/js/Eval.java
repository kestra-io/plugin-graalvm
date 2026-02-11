package io.kestra.plugin.graalvm.js;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
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
    title = "Execute inline JavaScript with GraalVM",
    description = "Runs inline JavaScript inside the task JVM via GraalVM. Access `runContext`, `logger`, and rendered variables from the bindings; declare names in `outputs` to return them. Allows file I/O and host class access restricted to `java.*` and `io.kestra.core.models.*`."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Execute a JavaScript script using the GraalVM scripting engine.",
            code = """
                id: evalJs
                namespace: company.team

                tasks:
                  - id: evalJs
                    type: io.kestra.plugin.graalvm.js.Eval
                    outputs:
                      - out
                      - map
                    script: |
                      (function() {
                        var Counter = Java.type('io.kestra.core.models.executions.metrics.Counter');
                        var File = Java.type('java.io.File');
                        var FileOutputStream = Java.type('java.io.FileOutputStream');
                        logger.info('Task started');
                        runContext.metric(Counter.of('total', 666, 'name', 'bla'));
                        map = {'test': 'here'};
                        var tempFile = runContext.workingDir().createTempFile().toFile();
                        var output = new FileOutputStream(tempFile);
                        output.write([72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100]);
                        out = runContext.storage().putFile(tempFile);
                        return {"map": map, "out": out};
                      })"""
        )
    },
    metrics = {
      @Metric(
         name = "records",
         type = Counter.TYPE,
         unit = "count",
         description = "Tracks a user defined numeric value emitted from the JavaScript script, such as the number of processed records or computed results."
      )
    }
)
public class Eval extends AbstractEval {
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "js");
    }
}
