package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.ApplicationContext;

/**
 * Standalone entry point launched in a forked JVM by {@link EvalTest} with {@code user.home}
 * pointed at an unusable path, simulating an unwritable OS-default GraalVM resource cache
 * (issue #40). A same-JVM test cannot verify this: the shared GraalVM {@code Engine} is a
 * one-time static holder, so the system property must be set before any GraalVM class loads.
 * Printing {@link #SUCCESS_MARKER} on success proves {@code AbstractScript.EngineHolder}
 * redirected the resource cache to a writable path under {@code java.io.tmpdir} before that
 * broken default was ever consulted.
 */
public class StdlibImportForkMain {
    static final String SUCCESS_MARKER = "STDLIB_IMPORTS_OK";

    public static void main(String[] args) {
        // Micronaut's ApplicationContext can leave non-daemon threads running after close(), which
        // would otherwise keep this forked JVM alive indefinitely. Force termination either way so
        // the parent test's bounded wait never has to rely on that shutdown behavior.
        try {
            try (ApplicationContext applicationContext = ApplicationContext.run()) {
                RunContextFactory runContextFactory = applicationContext.getBean(RunContextFactory.class);
                RunContext runContext = runContextFactory.of();

                Eval task = Eval.builder()
                    .id("stdlib-import-fork")
                    .type(Eval.class.getName())
                    .script(Property.ofValue(EvalTest.STDLIB_IMPORT_SCRIPT))
                    .build();

                task.run(runContext);
                System.out.println(SUCCESS_MARKER);
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
