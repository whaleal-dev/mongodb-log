package com.whaleal.mongodblog.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserLauncherTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BrowserLauncher.class)
            .withPropertyValues(
                    "mongodblog.open-browser=true",
                    "server.address=127.0.0.1",
                    "server.port=18080"
            );

    @Test
    void canBeCreatedBySpringWhenBrowserOpeningIsEnabled() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(BrowserLauncher.class));
    }

    @Test
    void opensOnlyTheConfiguredLocalAddressWhenApplicationIsReady() {
        List<URI> opened = new ArrayList<>();
        BrowserLauncher launcher = new BrowserLauncher(URI.create("http://127.0.0.1:18080"), opened::add);

        launcher.onApplicationEvent(null);

        assertThat(opened).containsExactly(URI.create("http://127.0.0.1:18080"));
    }
}
