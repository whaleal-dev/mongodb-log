package com.whaleal.mongodblog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "mongodblog.open-browser", havingValue = "true", matchIfMissing = true)
public class BrowserLauncher implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(BrowserLauncher.class);

    private final URI address;
    private final Consumer<URI> opener;

    @Autowired
    public BrowserLauncher(
            @Value("${server.address:127.0.0.1}") String host,
            @Value("${server.port:18080}") int port
    ) {
        this(URI.create("http://" + host + ":" + port), BrowserLauncher::openDefaultBrowser);
    }

    BrowserLauncher(URI address, Consumer<URI> opener) {
        this.address = address;
        this.opener = opener;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        opener.accept(address);
    }

    private static void openDefaultBrowser(URI address) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            LOG.warn("无法自动打开浏览器，请手动访问 {}", address);
            return;
        }
        try {
            Desktop.getDesktop().browse(address);
        } catch (IOException | RuntimeException e) {
            LOG.warn("无法自动打开浏览器，请手动访问 {}：{}", address, e.getMessage());
        }
    }
}
