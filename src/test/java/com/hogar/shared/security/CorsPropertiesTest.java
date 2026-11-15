package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CorsPropertiesTest {

    @Test
    void emptyOrMissingMeansDisabledAndBlanksAreIgnored() {
        assertThat(new CorsProperties(null).enabled()).isFalse();
        assertThat(new CorsProperties(List.of(" ", "")).enabled()).isFalse();
        assertThat(new CorsProperties(List.of(" http://localhost:5000 ")).allowedOrigins())
                .containsExactly("http://localhost:5000");
    }

    @Test
    void onlyExactOriginsAreAccepted() {
        for (String bad : List.of("*", "https://*.hogar.app", "http://localhost:5000/", "localhost:5000",
                "ftp://hogar.app", "https://hogar.app/app")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(bad))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new CorsProperties(List.of("https://app.hogar.test", "http://127.0.0.1:5000")).enabled()).isTrue();
    }
}
