package io.kestra.plugin.graalvm.ruby;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.graalvm.AbstractEval;
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
    title = "Execute a Ruby script using the GraalVM scripting engine."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Execute a Ruby script using the GraalVM scripting engine.",
            code = """
                    id: evalRuby
                    namespace: company.team

                    tasks:
                      - id: evalRuby
                        type: io.kestra.plugin.graalvm.ruby.Eval
                        outputs:
                          - map
                          - out
                        script: |
                          Counter = Java.type('io.kestra.core.models.executions.metrics.Counter')
                          FileOutputStream = Java.type('java.io.FileOutputStream')
                          # all variables must be imported before use
                          logger = Polyglot.import('logger')
                          runContext = Polyglot.import('runContext')
                          logger.info('Task started')
                          runContext.metric(Counter.of('total', 666, 'name', 'bla'))
                          map = {test: 'here'}
                          tempFile = runContext.workingDir().createTempFile().toFile()
                          output = FileOutputStream.new(tempFile)
                          output.write('Hello World'.bytes)
                          out = runContext.storage().putFile(tempFile)
                          return {map: map, out: out}"""
        )
    }
)
public class Eval extends AbstractEval {
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
