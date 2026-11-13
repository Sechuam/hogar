package com.hogar.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.hogar.auth.config.RateLimitProperties;
import com.hogar.shared.error.TooManyRequestsException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Rate limiting en memoria (Bucket4j) de los endpoints de autenticación, por clave:
 * IP remota + email normalizado en registro/login, solo IP en Google.
 *
 * <p>Los buckets viven en una caché Caffeine acotada ({@code maximumSize}) que expulsa los que
 * llevan un {@code refillPeriod} sin usarse: para entonces ya estarían llenos, así que olvidarlos
 * no cambia el resultado y la memoria no crece sin límite aunque un atacante rote emails.
 *
 * <p>Limitación conocida: es por instancia; con varias réplicas haría falta un backend compartido.
 */
@Service
public class AuthRateLimiter {

    static final String RATE_LIMITED = "rate-limited";
    static final long MAX_TRACKED_KEYS = 100_000;

    private final RateLimitProperties props;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;

    @Autowired
    public AuthRateLimiter(RateLimitProperties props, Clock clock) {
        this(props, clock, Ticker.systemTicker());
    }

    /** Para tests: el reloj gobierna la recarga de los buckets y el ticker la expiración de la caché. */
    AuthRateLimiter(RateLimitProperties props, Clock clock, Ticker ticker) {
        this.props = props;
        this.timeMeter = new ClockTimeMeter(clock);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterAccess(props.refillPeriod())
                .ticker(ticker)
                .build();
    }

    public void checkRegister(String remoteIp, String email) {
        consume("register|" + remoteIp + "|" + normalize(email));
    }

    public void checkLogin(String remoteIp, String email) {
        consume("login|" + remoteIp + "|" + normalize(email));
    }

    public void checkGoogle(String remoteIp) {
        consume("google|" + remoteIp);
    }

    private void consume(String key) {
        ConsumptionProbe probe = buckets.get(key, k -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
            throw new TooManyRequestsException(RATE_LIMITED,
                    "Demasiados intentos. Vuelve a intentarlo en " + retryAfter + " s.", retryAfter);
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(props.capacity()).refillGreedy(props.capacity(), props.refillPeriod()))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Adapta el {@link Clock} inyectado a Bucket4j para poder fijar el tiempo en los tests. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            return TimeUnit.MILLISECONDS.toNanos(clock.millis());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
