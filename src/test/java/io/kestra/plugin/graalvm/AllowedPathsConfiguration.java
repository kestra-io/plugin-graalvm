package io.kestra.plugin.graalvm;

import io.kestra.core.plugins.PluginConfiguration;
import io.kestra.core.plugins.PluginConfigurations;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

// Goes through the real plugin configuration lookup, so the allowed paths only apply to the configured task types.
// Only loaded by the tests that enable its environment, so the other tests keep running without plugin configuration.
@Factory
@Requires(env = AllowedPathsConfiguration.ENVIRONMENT)
public class AllowedPathsConfiguration {
    public static final String ENVIRONMENT = "allowed-paths";

    // static: the plugin configuration bean is created before the tests run
    public static final Path DATA_DIR = createDataDir();
    // next to the allowed directory, with a name starting with the allowed directory's name
    public static final Path OUTSIDE_FILE = DATA_DIR.resolveSibling(DATA_DIR.getFileName() + "-secret.txt");

    // the FileTransform tasks are left out on purpose
    private static final List<String> CONFIGURED_TYPES = List.of(
        "io.kestra.plugin.graalvm.python.Eval",
        "io.kestra.plugin.graalvm.js.Eval",
        "io.kestra.plugin.graalvm.ruby.Eval"
    );

    @Singleton
    @Replaces(PluginConfigurations.class)
    PluginConfigurations pluginConfigurations() {
        var values = Map.<String, Object>of(WorkingDirFileSystem.ALLOWED_PATHS, List.of(DATA_DIR.toString()));
        return new PluginConfigurations(IntStream.range(0, CONFIGURED_TYPES.size())
            .mapToObj(order -> new PluginConfiguration(order, CONFIGURED_TYPES.get(order), values))
            .toList());
    }

    private static void create(Path file, String content) throws IOException {
        Files.writeString(file, content).toFile().deleteOnExit();
    }

    private static Path createDataDir() {
        try {
            var dir = Files.createTempDirectory("kestra-graalvm-allowed-paths");
            // deleted in reverse order when the test JVM exits
            dir.toFile().deleteOnExit();
            create(dir.resolve("events.csv"), "id\n1\n2\n");
            create(dir.resolve("lib.js"), "var fromLib = 'loaded';");
            create(dir.resolve("lib.rb"), "$from_lib = 'loaded'\n");
            create(dir.resolveSibling(dir.getFileName() + "-secret.txt"), "secret");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
