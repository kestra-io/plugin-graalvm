package io.kestra.plugin.graalvm.python;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.StorageContext;
import io.kestra.plugin.graalvm.AbstractEval;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwBiConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute inline Python with GraalVM",
    description = "Runs inline Python inside the task JVM via GraalVM. Access `runContext`, `logger`, and rendered variables from the bindings; declare names in `outputs` to return them. Allows file I/O and host class access restricted to `java.*` and `io.kestra.core.models.*`. Optional `modules` preload Python files from content or `kestra://` URIs onto the module path."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Parse a downloaded JSON and update one of its fields.",
            code = """
                id: parse_json_data
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.core.http.Download
                    uri: http://xkcd.com/info.0.json

                  - id: graal
                    type: io.kestra.plugin.graalvm.python.Eval
                    outputs:
                      - data
                    script: |
                      data = {{ read(outputs.download.uri )}}
                      data["next_month"] = int(data["month"]) + 1
                """
        ),
        @Example(
            full = true,
            title = "Execute a Python script using the GraalVM scripting engine.",
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
                        output.write('Hello World'.encode('utf-8'))
                        out = runContext.storage().putFile(tempFile)
                        {"map": map, "out": out}"""
        ),
        @Example(
            full = true,
            title = "Define a Python module, then execute a script that imports this module using the GraalVM scripting engine.",
            code = """
                id: evalPython
                namespace: company.team

                tasks:
                  - id: evalPython
                    type: io.kestra.plugin.graalvm.python.Eval
                    modules:
                      hello.py: |
                        def hello(name):
                          return("Hello " + name)
                    script: |
                      import hello
                      logger.info(hello.hello("Kestra"))"""
        )
    },
    metrics = {
        @Metric(
            name = "records",
            type = Counter.TYPE,
            unit = "count",
            description = "Tracks a user defined numeric value emitted from the Python script, such as the number of processed records or computed results."
        )
    }
)
public class Eval extends AbstractEval {
    private static final Path MODULE_PATH = Path.of("__kestra_python");

    @Schema(
        title = "Inline Python modules to preload",
        description = "Map of filename to module content or a `kestra://` URI; files are written to a temporary module path before execution"
    )
    private Property<Map<String, String>> modules;

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (modules != null) {
            Path modulePath = runContext.workingDir().resolve(MODULE_PATH).resolve("src");
            Files.createDirectories(modulePath);
            runContext.render(modules).asMap(String.class, String.class).forEach(throwBiConsumer((k, v) ->
            {
                Path moduleFile = modulePath.resolve(k);
                byte[] content;
                if (v.startsWith(StorageContext.KESTRA_PROTOCOL)) {
                    content = runContext.storage().getFile(URI.create(v)).readAllBytes();
                } else {
                    content = v.getBytes();
                }
                Files.write(moduleFile, content);
            }));
        }

        return this.run(runContext, "python");
    }

    @Override
    protected Context.Builder contextBuilder(RunContext runContext) {
        if (modules == null) {
            return super.contextBuilder(runContext);
        } else {
            return GraalPyResources.contextBuilder(runContext.workingDir().resolve(MODULE_PATH));
        }
    }
}
