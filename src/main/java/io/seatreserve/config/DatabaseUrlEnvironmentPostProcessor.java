package io.seatreserve.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts the {@code postgres://user:pass@host:port/db?sslmode=require} URLs
 * that Neon, Fly, Render and Heroku hand out, and turns them into the JDBC URL,
 * username and password Spring expects. A {@code jdbc:} URL is used as is.
 *
 * <p>This keeps deployment to "paste the URL the provider gives you", with no
 * hand-translation step to get wrong.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "databaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("DATABASE_URL");
        Map<String, Object> converted = convert(url);
        if (!converted.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, converted));
        }
    }

    static Map<String, Object> convert(String url) {
        if (url == null || !(url.startsWith("postgres://") || url.startsWith("postgresql://"))) {
            return Map.of();
        }
        URI uri = URI.create(url);
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            jdbc.append('?').append(uri.getRawQuery());
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", jdbc.toString());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            properties.put("spring.datasource.username", decode(colon < 0 ? userInfo : userInfo.substring(0, colon)));
            if (colon >= 0) {
                properties.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
            }
        }
        return properties;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
