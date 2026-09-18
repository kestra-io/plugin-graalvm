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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Schema(
        title = "Advanced GraalVM context options",
        description = """
            Maps directly to GraalVM's `Context.Builder#options`: one entry per option key/value, for example `python.WarnOptions` or `js.ecmascript-version`.
            Keys that would weaken the sandbox already enforced by this task are rejected at execution time: `python.PosixModuleBackend`, any key related to host access or class loading, and any key prefixed with `engine.` or `sandbox.`.
            """
    )
    @PluginProperty(group = "advanced")
    protected Property<Map<String, String>> options;

    // Keys that would let a script weaken the sandbox already enforced by buildContext() below.
    private static final Set<String> DENIED_OPTION_KEYS = Set.of("python.PosixModuleBackend");
    private static final List<String> DENIED_OPTION_PREFIXES = List.of("engine.", "sandbox.");
    private static final List<String> DENIED_OPTION_KEYWORDS = List.of(
        "hostaccess", "hostclasslookup", "hostclassloading", "hostlookup", "classloader", "classloading"
    );

    protected Context buildContext(RunContext runContext, OutputStream out, OutputStream err) throws IllegalVariableEvaluationException {
        Context.Builder builder = contextBuilder(runContext)
            .engine(getEngine())
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
            // needed by Python C extensions such as ssl, sqlite3, and lzma
            .allowNativeAccess(allowNativeAccess())
            .currentWorkingDirectory(runContext.workingDir().path())
            .out(out)
            .err(err);

        runContext.render(this.options).asMap(String.class, String.class).forEach((key, value) -> {
            validateOptionKey(key);
            builder.option(key, value);
        });

        return builder.build();
    }

    private static void validateOptionKey(String key) {
        if (DENIED_OPTION_KEYS.contains(key)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: it lets the guest language bypass the sandboxed POSIX backend enforced by this task.");
        }
        if (DENIED_OPTION_PREFIXES.stream().anyMatch(key::startsWith)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: options prefixed with 'engine.' or 'sandbox.' can weaken the sandboxing enforced by this task.");
        }
        String lowerKey = key.toLowerCase();
        if (DENIED_OPTION_KEYWORDS.stream().anyMatch(lowerKey::contains)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: it would override the host-access or class-loading restrictions already enforced by this task.");
        }
    }

    protected Value getBindings(Context context, String languageId) {
        return context.getBindings(languageId);
    }

    protected boolean allowNativeAccess() {
        return false;
    }

    protected Context.Builder contextBuilder(RunContext runContext) {
        return Context.newBuilder().allowIO(IOAccess.ALL);
    }

    // initialization-on-demand holder idiom
    private static class EngineHolder {
        static final Engine INSTANCE = createEngine();

        private static Engine createEngine() {
            // GraalPy/TruffleRuby extract their stdlib to this cache directory on first use. If it's
            // unset, the default is a user home directory (~/.cache/org.graalvm.polyglot) that may be
            // unwritable, wiped, or corrupted in rootless / distributed deployments (issue #40). Default
            // it to a writable path under java.io.tmpdir, but never override an operator-set value.
            if (System.getProperty("polyglot.engine.userResourceCache") == null
                    && System.getProperty("polyglot.engine.resourcePath") == null) {
                try {
                    Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "kestra-graalvm-resource-cache");
                    Files.createDirectories(cacheDir);
                    System.setProperty("polyglot.engine.userResourceCache", cacheDir.toString());
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to create a writable GraalVM resource cache directory under java.io.tmpdir", e);
                }
            }

            return Engine.create();
        }
    }

    private Engine getEngine() {
        return EngineHolder.INSTANCE;
    }

    protected Source generateSource(String languageId, RunContext runContext) throws IllegalVariableEvaluationException {
        var rendered = runContext.render(this.script).as(String.class).orElseThrow();
        return Source.create(languageId, rendered);
    }
}
