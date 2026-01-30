package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
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
    title = "Transform rows with Python on GraalVM",
    description = "Reads rows from `from` (kestra:// internal storage or inline JSON), lets Python mutate `row` and optionally drop it, then writes an ION file. Set `concurrent` to parallelize (order not preserved); output URI points to the temporary transformed file."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
              id: transformPython
              namespace: company.team

              tasks:
                - id: download
                  type: io.kestra.plugin.core.http.Download
                  uri: https://dummyjson.com/carts/1
                - id: jsonToIon
                  type: io.kestra.plugin.serdes.json.JsonToIon
                  from: "{{outputs.download.uri}}"
                - id: transformPython
                  type: io.kestra.plugin.graalvm.python.FileTransform
                  from: "{{ outputs.jsonToIon.uri }}"
                  script: |
                    if row['id'] == 666:
                      # remove un-needed row
                      row = None
                    else:
                      # remove the 'products' column
                      row['products'] = None
                      # add a 'totalItems' column
                      row['totalItems'] = row['totalProducts'] * row['totalQuantity']"""
        )
    },
    metrics = {
      @Metric(
          name = "records",
          type = Counter.TYPE,
          unit = "count",
          description = "Number of records or entities processed by the Python script. This includes both modified and filtered rows from the input file."
      )
    }
)
public class FileTransform extends AbstractFileTransform {
    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "python");
    }
}
