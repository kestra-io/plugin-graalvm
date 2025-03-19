package io.kestra.plugin.graalvm;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractEval extends AbstractScript implements RunnableTask<AbstractEval.Output> {
    @Schema(
        title = "A List of outputs variables that will be usable in outputs."
    )
    protected Property<List<String>> outputs;

    protected Output run(RunContext runContext, String languageId) throws Exception {
        try (Context context = buildContext(runContext)) {
            var bindings = getBindings(context, languageId);
            // add all common vars to bindings in case of concurrency
            runContext.getVariables().forEach((key, value) -> bindings.putMember(key, value));
            bindings.putMember("runContext", new RunContextProxy(runContext));
            bindings.putMember("logger", runContext.logger());

            var source = generateSource(languageId, runContext);
            var result = context.eval(source);

            var renderedOutputs = runContext.render(this.outputs).asList(String.class);
            Output.OutputBuilder builder = Output.builder();
            if (result.canExecute()) {
                var results = result.execute();
                if (results.hasMembers() && !renderedOutputs.isEmpty()) {
                    builder.outputs(gatherOutputs(renderedOutputs, results));
                }
            }
            else if (result.isHostObject()){
                builder.result(result.asHostObject());
            }
            else if (result.hasMembers() && !renderedOutputs.isEmpty()) {
                builder.outputs(gatherOutputs(renderedOutputs, result));
            }

            return builder.build();
        }
    }

    private Map<String, Object> gatherOutputs(List<String> renderedOutputs, Value value) {
        Map<String, Object> outputs = new HashMap<>();
        renderedOutputs.forEach(s -> outputs.put(s, as(value.getMember(s))));
        return outputs;
    }

    private Object as(Value member) {
        if (member == null || member.canExecute()) { // we should not have an executable here, let's return null to be safe
            return null;
        }
        if (member.isString()) {
            return member.asString();
        }
        if (member.isNumber() && member.fitsInInt()) {
            return member.asInt();
        }
        if (member.isNumber() && member.fitsInLong()) {
            return member.asLong();
        }
        if (member.isNumber() && member.fitsInFloat()) {
            return member.asFloat();
        }
        if (member.isNumber() && member.fitsInDouble()) {
            return member.asDouble();
        }
        if (member.isProxyObject()) {
            return member.asProxyObject();
        }
        if (member.isHostObject()) {
            return member.asHostObject();
        }
        if (member.hasHashEntries()) {
            Map<String, Object> values = HashMap.newHashMap((int) member.getHashSize());
            Value iterator = member.getHashEntriesIterator();
            while (iterator.hasIteratorNextElement()) {
                Value value = iterator.getIteratorNextElement();
                values.put(value.getArrayElement(0).asString(), as(value.getArrayElement(1)));
            }
            return values;
        }
        if (member.hasMembers()) {
            // try to read members into a map
            Map<String, Object> values = new HashMap<>();
            member.getMemberKeys().forEach(key -> {
                values.put(key, as(member.getMember(key)));
            });
            return values;
        }

        // do our best to use a known type, this will crash with a ClassCastException if the type is not transformable
        return member.as(Object.class);
    }

    @Builder
    @Getter
    @ToString
    public static class Output implements io.kestra.core.models.tasks.Output {
        private Object result;

        @Schema(
            title = "The captured outputs as declared on the `outputs` task property."
        )
        private final Map<String, Object> outputs;
    }

}