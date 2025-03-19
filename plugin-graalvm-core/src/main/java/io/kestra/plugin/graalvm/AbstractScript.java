package io.kestra.plugin.graalvm;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
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

    @Getter(AccessLevel.NONE)
    private volatile transient Engine engine;

    protected Context buildContext(RunContext runContext) {
        return Context.newBuilder()
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
                // log to sysout TODO check that!
                .logHandler(System.out)
                .build();
    }

    private Engine getEngine() {
        // double-checked locking idiom
        Engine engine = this.engine;
        if (engine == null) {
            synchronized (this) {
                engine = this.engine;
                if (engine == null) {
                    engine = this.engine = Engine.create();
                }
            }
        }
        return engine;
    }

    protected Source generateSource(String languageId, RunContext runContext) throws IllegalVariableEvaluationException {
        var rendered = runContext.render(this.script).as(String.class).orElseThrow();
        return Source.create(languageId, rendered);
    }
}