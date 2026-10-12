package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import io.kestra.core.serializers.FileSerde;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
public class FileTransformTest {
    @Inject
    protected StorageInterface storageInterface;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        try (InputStream is = FileTransformTest.class.getClassLoader().getResourceAsStream("wikipedia_page_view.ion")) {
            var uri = storageInterface.put(
                TenantService.MAIN_TENANT,
                null,
                new URI("/" + IdUtils.create()),
                is
            );

            var runContext = runContextFactory.of();

            var fileTransform = FileTransform.builder()
                .id("fileTransform")
                .from(Property.ofValue(uri.toString()))
                .script(Property.ofValue("""
                  if row['title'] == 'Main_Page' or row['title'] == 'Special:Search' or row['title'] == '-':
                    # remove un-needed row
                    row = None
                  else:
                    # add a 'time' column
                    row['time'] = str(row['date'])[11:]
                    # modify the 'date' column to only keep the date part
                    row['date'] = str(row['date'])[0:10]
                    """))
                .build();

            var output = fileTransform.run(runContext);
            assertThat(output, notNullValue());
            assertThat(output.getUri(), notNullValue());
            try (InputStream ionIs = new BufferedInputStream(storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), FileSerde.BUFFER_SIZE)) {
                List<Object> result = new ArrayList<>();
                FileSerde.read(ionIs, result::add);
                assertThat(result.size(), is(7));
                assertThat(((Map<String, Object>) result.get(0)).get("title"), is("Sunita_Williams"));
            }
        }
    }

    @Test
    void fileAccessOutsideWorkingDirIsDenied(@TempDir Path outsideDir) throws Exception {
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");

        var fileTransform = FileTransform.builder()
            .id("fileTransform")
            .from(Property.ofValue("{\"title\": \"Main_Page\"}"))
            .script(Property.ofValue("row['secret'] = open('%s').read()".formatted(secret)))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> fileTransform.run(runContextFactory.of()));
        assertThat(exception.getMessage(), startsWith("PermissionError"));
    }

    @Test
    void rows() throws Exception {
        assertThat(transform("""
                if row['id'] == 1:
                    rows = [{'id': 1, 'copy': 1}, {'id': 1, 'copy': 2}]
                """), is(List.of("{id=1, copy=1}", "{id=1, copy=2}", "{id=2}")));
    }

    @Test
    void emptyRowsDropsTheRow() throws Exception {
        assertThat(transform("""
                if row['id'] == 1:
                    rows = []
                """), is(List.of("{id=2}")));
    }

    @Test
    void rowsMustBeAList() {
        var exception = assertThrows(Exception.class, () -> transform("""
                rows = {'id': row['id']}
                """));
        assertThat(exception.getMessage(), containsString("`rows` must be a list of rows"));
    }

    // runs the script on the rows {id=1} and {id=2}, and returns the output rows as strings
    private List<String> transform(String script) throws Exception {
        var input = new ByteArrayOutputStream();
        FileSerde.writeAll(input, Flux.just(new LinkedHashMap<>(Map.of("id", 1)), new LinkedHashMap<>(Map.of("id", 2)))).block();
        var uri = storageInterface.put(TenantService.MAIN_TENANT, null, new URI("/" + IdUtils.create() + ".ion"), new ByteArrayInputStream(input.toByteArray()));

        var output = FileTransform.builder()
            .id("fileTransform")
            .from(Property.ofValue(uri.toString()))
            .script(Property.ofValue(script))
            .build()
            .run(runContextFactory.of());

        try (InputStream ionIs = new BufferedInputStream(storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), FileSerde.BUFFER_SIZE)) {
            List<Object> result = new ArrayList<>();
            FileSerde.read(ionIs, result::add);
            return result.stream().map(String::valueOf).toList();
        }
    }
}
