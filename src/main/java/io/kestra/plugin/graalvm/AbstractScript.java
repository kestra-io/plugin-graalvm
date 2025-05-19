package io.kestra.plugin.graalvm;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractScript extends Task {
    @Schema(
        title = "The script to evaluate"
    )
    @NotNull
    protected Property<String> script;

    protected Context buildContext(RunContext runContext) {
        return contextBuilder(runContext)
            .engine(getEngine())
            // allow I/O
            .allowIO(IOAccess.ALL)
            // allow host access with a curated default
            .allowHostAccess(HostAccess
                    .newBuilder(HostAccess.EXPLICIT)
                    .allowArrayAccess(true).allowListAccess(true).allowBufferAccess(true).allowIterableAccess(true).allowIteratorAccess(true).allowMapAccess(true).allowPublicAccess(true)
                    .build()
            )
            // allow loading class
            .allowHostClassLoading(true)
            // restrict loading class to java.* and io.kestra.core.models.*
            .allowHostClassLookup(name -> name.startsWith("java.") || name.startsWith("io.kestra.core.models"))
            // log to the run context logger
            .logHandler(new SLF4JJULHandler(runContext.logger()))
            // needed for Ruby
            .allowPolyglotAccess(PolyglotAccess.ALL)
            // needed for Ruby
            .allowCreateThread(true)
            .currentWorkingDirectory(runContext.workingDir().path())
            .build();
    }

    protected Value getBindings(Context context, String languageId) {
        return context.getBindings(languageId);
    }

    protected Context.Builder contextBuilder(RunContext runContext) {
        return Context.newBuilder();
    }

    // initialization-on-demand holder idiom
    private static class EngineHolder {
        static final Engine INSTANCE = Engine.create();
    }

    private Engine getEngine() {
        return EngineHolder.INSTANCE;
    }

    protected Source generateSource(String languageId, RunContext runContext) throws IllegalVariableEvaluationException {
        var rendered = runContext.render(this.script).as(String.class).orElseThrow();
        return Source.create(languageId, rendered);
    }
}