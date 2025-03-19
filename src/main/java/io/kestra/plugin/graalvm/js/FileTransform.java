package io.kestra.plugin.graalvm.js;

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
            full = true,
            code = """
            id: transformJs
            namespace: company.team

            inputs:
              - id: file
                type: FILE

            tasks:
              - id: transformJs
                type: io.kestra.plugin.graalvm.js.FileTransform
                from: "{{ inputs.file }}"
                script: |
                  if (row['title'] === 'Main_Page' || row['title'] === 'Special:Search' || row['title'] === '-') {
                    // remove un-needed row
                    row = null
                  } else {
                    // add a 'time' column
                    row['time'] = String(row['date']).substring(11)
                    // modify the 'date' column to only keep the date part
                    row['date'] = String(row['date']).substring(0, 10)
                  }
            """
        )
    },
    beta = true
)
public class FileTransform extends AbstractFileTransform {
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "js");
    }
}
