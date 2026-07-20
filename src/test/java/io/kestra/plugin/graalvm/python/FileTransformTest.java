package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.*;
import io.kestra.core.serializers.FileSerde;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

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
}
