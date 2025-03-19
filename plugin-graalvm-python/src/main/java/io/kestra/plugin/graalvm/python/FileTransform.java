package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.graalvm.AbstractFileTransform;
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
        title = "Execute a  JavaScript script using the GraalVM scripting engine."
)
@Plugin(
        examples = {
                @Example(
                        code = """
                outputs:
                  - out
                  - map
                script: |
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
                )
        },
    beta = true
)
public class FileTransform extends AbstractFileTransform {
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "python");
    }
}
