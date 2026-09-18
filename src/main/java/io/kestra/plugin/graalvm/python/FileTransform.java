package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.enums.MonacoLanguages;
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
    description = "Streams rows from `from` (kestra:// URI, map, or list), lets Python mutate `row`, and writes the result as an ION file. Set `concurrent` to parallelize (order not preserved). Set `row = None` to drop a record; set `rows` array to emit multiple rows. Supports C-extension-backed stdlib modules such as `ssl`, `sqlite3`, and `lzma`, which requires enabling native access at the GraalVM engine level. Standard OS process APIs (`os.system`, `subprocess`) stay blocked, but this grant cannot be scoped down further: a script that reaches native code directly (e.g. `ctypes`) can still execute arbitrary OS commands or manipulate process memory. Unlike Kestra's `Script`/`Shell` tasks, which typically run in an isolated container or process via a `TaskRunner`, this code runs inline in the worker JVM process itself, so it has direct access to the worker process's own memory, file descriptors, and any secrets or other task state resident in that JVM. Only run scripts from users already trusted with the flow's credentials and infrastructure, as with any script task."
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

    @PluginProperty(language = MonacoLanguages.PYTHON)
    @Override
    public Property<String> getScript() {
        return super.getScript();
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        return this.run(runContext, "python");
    }

    @Override
    protected boolean allowNativeAccess() {
        return true;
    }
}
