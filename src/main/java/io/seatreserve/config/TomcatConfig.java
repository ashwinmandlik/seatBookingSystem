package io.seatreserve.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Per-connection socket buffer sizes. Tomcat's default is 8 KB each way per
 * connection; our requests and responses are a few hundred bytes, so small
 * buffers let a host keep thousands of waiting connections open cheaply
 * (larger payloads still work, read in several passes).
 */
@Configuration
class TomcatConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> socketBuffers(
            @Value("${seatreserve.tomcat.socket-buffer-bytes:8192}") int bufferBytes) {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("socket.appReadBufSize", String.valueOf(bufferBytes));
            connector.setProperty("socket.appWriteBufSize", String.valueOf(bufferBytes));
        });
    }
}
