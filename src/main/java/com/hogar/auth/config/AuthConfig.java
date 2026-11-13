package com.hogar.auth.config;

import com.hogar.auth.service.NimbusGoogleIdTokenVerifier;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GoogleProperties.class, RateLimitProperties.class})
class AuthConfig {

    /**
     * Decoder de ID tokens de Google (RS256 contra su JWKS, que Nimbus cachea). <b>No</b> es
     * {@code @Primary}: el resource server sigue usando el decoder HS256 de los access tokens
     * propios, así que un ID token de Google nunca autentica una llamada a la API.
     */
    @Bean
    @Qualifier(NimbusGoogleIdTokenVerifier.DECODER_QUALIFIER)
    JwtDecoder googleIdTokenDecoder(GoogleProperties props, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(NimbusGoogleIdTokenVerifier.validator(props.clientIds(), clock));
        return decoder;
    }
}
