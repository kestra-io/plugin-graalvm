package io.kestra.plugin.graalvm.js;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.is;

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
                      if (row['title'] === 'Main_Page' || row['title'] === 'Special:Search' || row['title'] === '-') {
                        // remove un-needed row
                        row = null
                      } else {
                        // add a 'time' column
                        row['time'] = String(row['date']).substring(11)
                        // modify the 'date' column to only keep the date part
                        row['date'] = String(row['date']).substring(0, 10)
                      }
                    """))
                .build();

            var output = fileTransform.run(runContext);
            assertThat(output, notNullValue());
            assertThat(output.getUri(), notNullValue());
            // Verify structured ION deserialization works (ION binary migration validation)
            try (InputStream ionIs = new BufferedInputStream(storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), FileSerde.BUFFER_SIZE)) {
                List<Object> result = new ArrayList<>();
                FileSerde.read(ionIs, result::add);
                assertThat(result.size(), is(7));
                assertThat(((Map<String, Object>) result.get(0)).get("title"), is("Sunita_Williams"));
            }
        }
    }

    @Test
    void rows() throws Exception {
        assertThat(transform("""
                if (row['id'] == 1) {
                  rows = [{id: 1, copy: 1}, {id: 1, copy: 2}];
                }
                """), is(List.of("{id=1, copy=1}", "{id=1, copy=2}", "{id=2}")));
    }

    @Test
    void rowsDeclaredWithVar() throws Exception {
        assertThat(transform("""
                if (row['id'] == 1) {
                  var rows = [{id: 1, copy: 1}, {id: 1, copy: 2}];
                }
                """), is(List.of("{id=1, copy=1}", "{id=1, copy=2}", "{id=2}")));
    }

    @Test
    void emptyRowsDropsTheRow() throws Exception {
        assertThat(transform("""
                if (row['id'] == 1) {
                  rows = [];
                }
                """), is(List.of("{id=2}")));
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
