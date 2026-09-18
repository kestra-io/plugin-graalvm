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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
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
    private static final Logger LOG = LoggerFactory.getLogger(AbstractScript.class);

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
        var builder = contextBuilder(runContext)
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
            // needed by Python C extensions such as ssl, sqlite3, and lzma; see hardenNativeAccess()
            // below for the compensating controls this engine-wide grant requires
            .allowNativeAccess(allowNativeAccess())
            .currentWorkingDirectory(runContext.workingDir().path())
            .out(out)
            .err(err);

        runContext.render(this.options).asMap(String.class, String.class).forEach((key, value) -> {
            validateOptionKey(key);
            builder.option(key, value);
        });

        var context = builder.build();
        hardenNativeAccess(context);
        return context;
    }

    private static void validateOptionKey(String key) {
        if (DENIED_OPTION_KEYS.contains(key)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: it lets the guest language bypass the sandboxed POSIX backend enforced by this task. Remove it from `options`; this restriction is permanent and cannot be overridden.");
        }
        if (DENIED_OPTION_PREFIXES.stream().anyMatch(key::startsWith)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: options prefixed with 'engine.' or 'sandbox.' can weaken the sandboxing enforced by this task. Remove it from `options`; engine- and sandbox-level configuration is not exposed to task scripts.");
        }
        var lowerKey = key.toLowerCase();
        if (DENIED_OPTION_KEYWORDS.stream().anyMatch(lowerKey::contains)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: it would override the host-access or class-loading restrictions already enforced by this task. Remove it from `options`; these restrictions are permanent and cannot be overridden.");
        }
    }

    protected Value getBindings(Context context, String languageId) {
        return context.getBindings(languageId);
    }

    protected boolean allowNativeAccess() {
        return false;
    }

    /**
     * Called once, right after context creation, for languages that override {@link #allowNativeAccess()}
     * to {@code true}. No-op by default.
     * <p>
     * GraalVM's {@code allowNativeAccess} is an engine-wide, all-or-nothing switch: there is no
     * finer-grained polyglot API to grant native access to GraalVM's own bundled C-extension loader
     * (needed for Python's ssl/sqlite3/lzma) while denying it to arbitrary guest-script code (e.g. a
     * Python script calling {@code ctypes.CDLL(...).system(...)}). Neither {@code SandboxPolicy}
     * (a coarse TRUSTED/CONSTRAINED/ISOLATED/UNTRUSTED build-time check, not a runtime restriction) nor
     * GraalPy's {@code python.NativeModules} option (selects the LLVM-bitcode C-extension backend, but
     * still requires native access to be allowed at all, confirmed experimentally: GraalPy raises
     * "Cannot run any C extensions because native access is not allowed" regardless of that option)
     * offer a way to scope native access down further. Overriding this hook lets a language close the
     * specific native-FFI escape hatches it knows about as defense-in-depth on top of that unavoidable
     * engine-wide grant; see {@code PythonNativeAccessGuard} for Python's mitigation and its documented
     * residual risk.
     */
    protected void hardenNativeAccess(Context context) {
        // no-op: only languages that enable native access need to further restrict it
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
                var cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "kestra-graalvm-resource-cache");
                try {
                    Files.createDirectories(cacheDir);
                    System.setProperty("polyglot.engine.userResourceCache", cacheDir.toString());
                } catch (IOException e) {
                    // Do NOT throw from a static field initializer: per JLS class-initialization
                    // semantics, an exception here would poison this holder class for the entire JVM
                    // lifetime, turning every subsequent call into a NoClassDefFoundError instead of the
                    // original exception -- a transient tmpdir permission issue on worker boot would
                    // become a permanent outage for every GraalVM script task until process restart.
                    // Fall back to GraalVM's own default resource-cache resolution instead: issue #40 may
                    // resurface, but only in this specific failure mode, which is strictly better than a
                    // total, unrecoverable outage.
                    LOG.warn("Unable to create a writable GraalVM resource cache directory at '{}'; falling back to GraalVM's default resource cache resolution, which may be unwritable in rootless or distributed deployments (see issue #40)", cacheDir, e);
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
