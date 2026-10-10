package io.kestra.plugin.graalvm.js;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.enums.MonacoLanguages;
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
    description = "Runs inline JavaScript inside the task JVM via GraalVM. Access `runContext`, `logger`, and rendered variables from the bindings; declare names in `outputs` to return them. File access is limited to the task working directory. Operators can let scripts read extra host directories, read-only, with the `allowed-paths` plugin configuration of this task type."
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
                        var ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
                        var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
                        logger.info('Task started');
                        runContext.metric(Counter.of('total', 666, 'name', 'bla'));
                        map = {'test': 'here'};
                        var bytes = StandardCharsets.UTF_8.encode('Hello World');
                        var content = new ByteArrayInputStream(bytes.array(), 0, bytes.limit());
                        out = runContext.storage().putFile(content, 'out.txt');
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

    @PluginProperty(language = MonacoLanguages.JAVASCRIPT)
    @Override
    public Property<String> getScript() {
        return super.getScript();
    }
    
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "js");
    }
}
