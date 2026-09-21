package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.ApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Standalone entry point launched in a forked JVM by {@link EvalTest}, with the resource-cache
 * directory's exact path (which is now PID-suffixed, see {@code AbstractScript.EngineHolder})
 * pre-occupied by a regular file, forcing {@code Files.createDirectories(cacheDir)} to fail inside
 * {@code AbstractScript.EngineHolder.createEngine()}'s static field initializer. A same-JVM test
 * cannot verify this: the shared GraalVM {@code Engine} is a one-time static holder, so the failure
 * must be injected before any GraalVM class loads. Since the PID is only known once this process has
 * started, {@code args[0]} is a "go" marker file: this main waits for it to appear before touching any
 * GraalVM class, giving the parent test time to read {@code ProcessHandle.pid()} and create the
 * colliding file at the real, now-known path.
 * <p>
 * Runs two scripts back to back: if the caught {@code IOException} were rethrown from the static
 * initializer instead of being logged and swallowed, the first {@code Eval.run()} would already fail
 * with the original exception wrapped in an {@code ExceptionInInitializerError}, and per JLS
 * class-initialization semantics every subsequent access to {@code EngineHolder} -- including this
 * forked process's second script -- would instead fail with {@code NoClassDefFoundError}. Both
 * scripts succeeding proves the holder falls back to GraalVM's own default resource-cache resolution
 * instead of being permanently poisoned for the rest of the JVM's lifetime.
 */
public class EngineHolderFallbackForkMain {
    static final String SUCCESS_MARKER = "ENGINE_HOLDER_FALLBACK_OK";

    public static void main(String[] args) {
        // Micronaut's ApplicationContext can leave non-daemon threads running after close(), which
        // would otherwise keep this forked JVM alive indefinitely. Force termination either way so
        // the parent test's bounded wait never has to rely on that shutdown behavior.
        try {
            awaitGoSignal(Path.of(args[0]));

            try (ApplicationContext applicationContext = ApplicationContext.run()) {
                RunContextFactory runContextFactory = applicationContext.getBean(RunContextFactory.class);

                for (int i = 0; i < 2; i++) {
                    RunContext runContext = runContextFactory.of();
                    Eval task = Eval.builder()
                        .id("engine-holder-fallback-fork")
                        .type(Eval.class.getName())
                        .script(Property.ofValue("1 + 1"))
                        .build();

                    task.run(runContext);
                }
                System.out.println(SUCCESS_MARKER);
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void awaitGoSignal(Path goFile) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!Files.exists(goFile)) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Timed out waiting for go-signal file: " + goFile);
            }
            Thread.sleep(20);
        }
    }
}
