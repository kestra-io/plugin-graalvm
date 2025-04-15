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
    title = "Transform a file from Kestra with a Ruby script using the GraalVM scripting engine."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: transformRuby
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.core.http.Download
                    uri: https://dummyjson.com/carts/1
                  - id: jsonToIon
                    type: io.kestra.plugin.serdes.json.JsonToIon
                    from: "{{outputs.download.uri}}"
                  - id: transformRuby
                    type: io.kestra.plugin.graalvm.ruby.FileTransform
                    from: "{{ outputs.jsonToIon.uri }}"
                    script: |
                      row = Polyglot.import('row')
                      if row[:id] == 55
                        # remove un-needed row
                        Polyglot.export('row', nil)
                      else
                        # remove the 'products' column
                        row[:products] = nil
                        # add a 'totalItems' column
                        row[:totalItems] = row[:totalProducts] * row[:totalQuantity]
                      end"""
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
