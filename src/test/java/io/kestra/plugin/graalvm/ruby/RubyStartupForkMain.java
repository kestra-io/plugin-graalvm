package io.kestra.plugin.graalvm.ruby;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.ApplicationContext;

// Runs a Ruby script in a forked JVM so the GraalVM engine is created with the JVM args set by the test.
public class RubyStartupForkMain {
    static final String SUCCESS_MARKER = "RUBY_STARTUP_OK";

    public static void main(String[] args) {
        // Micronaut can leave non-daemon threads running after close(), so always exit explicitly
        try {
            try (ApplicationContext applicationContext = ApplicationContext.run()) {
                var runContext = applicationContext.getBean(RunContextFactory.class).of();

                Eval.builder()
                    .id("ruby-startup-fork")
                    .type(Eval.class.getName())
                    .script(Property.ofValue("1 + 1"))
                    .build()
                    .run(runContext);
                System.out.println(SUCCESS_MARKER);
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
