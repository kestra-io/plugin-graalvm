package io.kestra.plugin.graalvm.ruby;

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
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

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
                id: graal-transform-ruby
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: transformRuby
                    type: io.kestra.plugin.graalvm.ruby.FileTransform
                    from: "{{ inputs.file }}"
                    script: |
                      row = Polyglot.import('row')
                      if row[:title] == 'Main_Page' || row[:title] == 'Special:Search' || row[:title] == '-'
                        # remove un-needed row
                        Polyglot.export('row', nil)
                      else
                        # add a 'time' column
                        row[:time] = row[:date].toString[11..]
                        # modify the 'date' column to only keep the date part
                        row[:date] = row[:date].toString[0,10]
                      end)"""
        )
    },
    beta = true
)
public class FileTransform extends AbstractFileTransform {
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "ruby");
    }

    // Standard bindings didn't work with Ruby so we must use Polyglot bindings
    @Override
    protected Value getBindings(Context context, String languageId) {
        return context.getPolyglotBindings();
    }
}
