package io.kestra.plugin.graalvm;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.ApplicationContext;

// Forked JVM entry point: java.io.tmpdir is a symlink, so task working dirs are reached through a host symlink
// (like /var -> /private/var on macOS).
public class SymlinkedTmpDirForkMain {
    static final String SUCCESS_MARKER = "SYMLINKED_TMPDIR_OK";

    public static void main(String[] args) {
        try {
            try (ApplicationContext applicationContext = ApplicationContext.run()) {
                var factory = applicationContext.getBean(RunContextFactory.class);

                io.kestra.plugin.graalvm.js.Eval.builder()
                    .id("js").type(io.kestra.plugin.graalvm.js.Eval.class.getName())
                    .script(Property.ofValue("if (1 + 1 !== 2) { throw new Error('unexpected'); }"))
                    .build().run(factory.of());

                io.kestra.plugin.graalvm.python.Eval.builder()
                    .id("python").type(io.kestra.plugin.graalvm.python.Eval.class.getName())
                    .script(Property.ofValue("""
                        import json, colorsys
                        with open('f.txt', 'w') as f:
                            f.write('x')
                        with open('f.txt') as f:
                            assert f.read() == 'x'
                        """))
                    .build().run(factory.of());
                System.out.println(SUCCESS_MARKER);
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
