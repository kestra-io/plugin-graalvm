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

    @Test
    void columnsReordersOutput() throws Exception {
        assertThat(transform("""
                row['c'] = 3;
                row['b'] = 2;
                row['a'] = 1;
                """, List.of("a", "b", "c", "id")),
            is(List.of("{a=1, b=2, c=3, id=1}", "{a=1, b=2, c=3, id=2}")));
    }

    @Test
    void columnsPartialOverlap() throws Exception {
        assertThat(transform("""
                row['x'] = 99;
                """, List.of("x", "id")),
            is(List.of("{x=99, id=1}", "{x=99, id=2}")));
    }

    @Test
    void columnsIncludesOnlyListedColumns() throws Exception {
        assertThat(transform("""
                row['extra'] = 'e';
                """, List.of("id")),
            is(List.of("{id=1}", "{id=2}")));
    }

    @Test
    void noColumnsPreservesOriginalBehavior() throws Exception {
        assertThat(transform("""
                row['z'] = 0;
                """),
            is(List.of("{id=1, z=0}", "{id=2, z=0}")));
    }

    @Test
    void emptyColumnsListPreservesOriginalOrder() throws Exception {
        assertThat(transform("""
                row['z'] = 0;
                row['a'] = 9;
                """, List.of()),
            is(List.of("{id=1, z=0, a=9}", "{id=2, z=0, a=9}")));
    }

    @Test
    void missingColumnWrittenAsNull() throws Exception {
        assertThat(transform("""
                row['a'] = 1;
                """, List.of("missing", "a", "id")),
            is(List.of("{missing=null, a=1, id=1}", "{missing=null, a=1, id=2}")));
    }

    @Test
    void duplicateColumnsInConfiguration() throws Exception {
        assertThat(transform("""
                row['b'] = 2;
                row['a'] = 1;
                """, List.of("a", "a", "id", "id", "b")),
            is(List.of("{a=1, id=1, b=2}", "{a=1, id=2, b=2}")));
    }

    @Test
    void onlyListedColumnsIncludedFromMultiColumnRow() throws Exception {
        var input = new ByteArrayOutputStream();
        var row1 = new LinkedHashMap<String, Object>();
        row1.put("first", 1);
        row1.put("second", 2);
        row1.put("target", 3);
        row1.put("fourth", 4);
        row1.put("fifth", 5);

        FileSerde.writeAll(input, Flux.just(row1)).block();
        var uri = storageInterface.put(TenantService.MAIN_TENANT, null, new URI("/" + IdUtils.create() + ".ion"), new ByteArrayInputStream(input.toByteArray()));

        var output = FileTransform.builder()
            .id("fileTransform")
            .from(Property.ofValue(uri.toString()))
            .columns(Property.ofValue(List.of("target")))
            .script(Property.ofValue(""))
            .build()
            .run(runContextFactory.of());

        try (InputStream ionIs = new BufferedInputStream(storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), FileSerde.BUFFER_SIZE)) {
            List<Object> result = new ArrayList<>();
            FileSerde.read(ionIs, result::add);
            assertThat(result.stream().map(String::valueOf).toList(),
                is(List.of("{target=3}")));
        }
    }

    @Test
    void columnsWithInlineJsonInput() throws Exception {
        var output = FileTransform.builder()
            .id("fileTransform")
            .from(Property.ofValue("[{\"z\": 10, \"y\": 20, \"x\": 30}]"))
            .columns(Property.ofValue(List.of("x", "z")))
            .script(Property.ofValue(""))
            .build()
            .run(runContextFactory.of());

        try (InputStream ionIs = new BufferedInputStream(storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), FileSerde.BUFFER_SIZE)) {
            List<Object> result = new ArrayList<>();
            FileSerde.read(ionIs, result::add);
            assertThat(result.stream().map(String::valueOf).toList(),
                is(List.of("{x=30, z=10}")));
        }
    }

    private List<String> transform(String script) throws Exception {
        return transform(script, null);
    }

    private List<String> transform(String script, List<String> columns) throws Exception {
        var input = new ByteArrayOutputStream();
        FileSerde.writeAll(input, Flux.just(new LinkedHashMap<>(Map.of("id", 1)), new LinkedHashMap<>(Map.of("id", 2)))).block();
        var uri = storageInterface.put(TenantService.MAIN_TENANT, null, new URI("/" + IdUtils.create() + ".ion"), new ByteArrayInputStream(input.toByteArray()));

        var builder = FileTransform.builder()
            .id("fileTransform")
            .from(Property.ofValue(uri.toString()))
            .script(Property.ofValue(script));

        if (columns != null) {
            builder.columns(Property.ofValue(columns));
        }

        var output = builder.build().run(runContextFactory.of());

        try (InputStream ionIs = new BufferedInputStream(storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), FileSerde.BUFFER_SIZE)) {
            List<Object> result = new ArrayList<>();
            FileSerde.read(ionIs, result::add);
            return result.stream().map(String::valueOf).toList();
        }
    }
}
