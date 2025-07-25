package io.kestra.plugin.graalvm.js;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URI;

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
            try (InputStream resultIs = storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri())) {
                String results = new String(resultIs.readAllBytes());
                assertThat(results, is("""
                    {date:"2025-03-19",title:"Sunita_Williams",views:5969,time:"12:00:00Z"}
                    {date:"2025-03-19",title:"Adolescence_(TV_series)",views:3188,time:"12:00:00Z"}
                    {date:"2025-03-19",title:"1989_Tiananmen_Square_protests_and_massacre",views:2658,time:"12:00:00Z"}
                    {date:"2025-03-19",title:"Deaths_in_2025",views:2292,time:"12:00:00Z"}
                    {date:"2025-03-19",title:"Apple_Network_Server",views:1835,time:"12:00:00Z"}
                    {date:"2025-03-19",title:"ChatGPT",views:1715,time:"12:00:00Z"}
                    {date:"2025-03-19",title:"Portal:Current_events",views:1462,time:"12:00:00Z"}"""));
            }
        }
    }
}
