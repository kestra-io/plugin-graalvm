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

import java.io.OutputStream;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractScript extends Task {
    @Schema(
        title = "Script body to execute",
        description = "Template-rendered source code run by GraalVM in the selected language; flow variables are resolved before execution"
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> script;

    protected Context buildContext(RunContext runContext, OutputStream out, OutputStream err) {
        return contextBuilder(runContext)
            .engine(getEngine())
            // allow I/O
            .allowIO(IOAccess.ALL)
            // allow host access with a curated default
            .allowHostAccess(HostAccess
                    .newBuilder(HostAccess.EXPLICIT)
                    .allowArrayAccess(true).allowListAccess(true).allowBufferAccess(true).allowIterableAccess(true).allowIteratorAccess(true).allowMapAccess(true).allowPublicAccess(true)
                    // Deny method invocation on dangerous types even when an instance is obtained
                    // indirectly (e.g. via Object.getClass()). This closes reflection-based bypasses
                    // of allowHostClassLookup such as `x.getClass().getClassLoader().loadClass(...)`.
                    .denyAccess(Class.class)
                    .denyAccess(ClassLoader.class)
                    .denyAccess(java.lang.reflect.AccessibleObject.class) // Method, Field, Constructor
                    .denyAccess(java.lang.reflect.Executable.class)
                    .denyAccess(Runtime.class)
                    .denyAccess(ProcessBuilder.class)
                    .denyAccess(Process.class)
                    .denyAccess(System.class)
                    .build()
            )
            // allow loading class
            .allowHostClassLoading(true)
            // restrict loading class to java.* and io.kestra.core.models.* but deny
            // dangerous packages that allow OS command execution or arbitrary reflection
            .allowHostClassLookup(name -> {
                // Block Java classes/packages that enable OS-level command execution, reflection,
                // class loading, JVM control, and networking. This is defense-in-depth on top of the
                // HostAccess denyAccess rules above (which also block indirectly-obtained instances).
                if (name.equals("java.lang.Runtime")
                        || name.equals("java.lang.ProcessBuilder")
                        || name.startsWith("java.lang.Process")
                        || name.equals("java.lang.System")
                        || name.equals("java.lang.Class")
                        || name.equals("java.lang.ClassLoader")
                        || name.startsWith("java.lang.reflect.")
                        || name.startsWith("java.lang.invoke.")
                        || name.startsWith("java.net.")
                        || name.startsWith("java.rmi.")
                        || name.startsWith("javax.script.")
                        || name.startsWith("sun.")
                        || name.startsWith("com.sun.")) {
                    return false;
                }
                return name.startsWith("java.") || name.startsWith("io.kestra.core.models");
            })
            // log to the run context logger
            .logHandler(new SLF4JJULHandler(runContext.logger()))
            // needed for Ruby
            .allowPolyglotAccess(PolyglotAccess.ALL)
            // needed for Ruby
            .allowCreateThread(true)
            .currentWorkingDirectory(runContext.workingDir().path())
            .out(out)
            .err(err)
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
