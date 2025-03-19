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
            full = true,
            code = """
        id: transformPython
        namespace: company.team

        inputs:
          - id: file
            type: FILE

        tasks:
          - id: transformPython
            type: io.kestra.plugin.graalvm.python.FileTransform
            from: "{{ inputs.file }}"
            script: |
              if row['title'] == 'Main_Page' or row['title'] == 'Special:Search' or row['title'] == '-':
                # remove un-needed row
                row = None
              else:
                # add a 'time' column
                row['time'] = str(row['date'])[11:]
                # modify the 'date' column to only keep the date part
                row['date'] = str(row['date'])[0:10]
        """
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
