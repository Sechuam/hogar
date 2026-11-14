package com.hogar.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/households");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> seenInMdc = new AtomicReference<>();
    private final FilterChain recordingChain = (req, res) -> seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void validIncomingHeaderIsReused() throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "abc-123_X.y");

        filter.doFilter(request, response, recordingChain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_X.y");
        assertThat(seenInMdc.get()).isEqualTo("abc-123_X.y");
    }

    @Test
    void headerOfExactly64CharsIsAccepted() throws Exception {
        String max = "a".repeat(64);
        request.addHeader(RequestIdFilter.HEADER, max);

        filter.doFilter(request, response, recordingChain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(max);
    }

    @Test
    void missingHeaderGeneratesUuid() throws Exception {
        filter.doFilter(request, response, recordingChain);

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(seenInMdc.get()).isEqualTo(generated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "id\r\nX-Injected: 1", "con espacio", "ñandú", "id;drop", "<script>"})
    void invalidHeaderIsReplacedByUuid(String invalid) throws Exception {
        request.addHeader(RequestIdFilter.HEADER, invalid);

        filter.doFilter(request, response, recordingChain);

        String used = response.getHeader(RequestIdFilter.HEADER);
        assertThat(used).isNotEqualTo(invalid);
        assertThat(UUID.fromString(used)).isNotNull();
        assertThat(seenInMdc.get()).isEqualTo(used);
    }

    @Test
    void tooLongHeaderIsReplacedByUuid() throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "a".repeat(65));

        filter.doFilter(request, response, recordingChain);

        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER))).isNotNull();
    }

    @Test
    void mdcIsClearedAfterRequest() throws Exception {
        filter.doFilter(request, response, recordingChain);

        assertThat(seenInMdc.get()).isNotNull();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        assertThat(RequestIdFilter.current()).isEmpty();
    }

    @Test
    void mdcIsClearedEvenIfChainThrows() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void currentExposesRequestIdDuringRequest() throws Exception {
        AtomicReference<String> current = new AtomicReference<>();
        request.addHeader(RequestIdFilter.HEADER, "trace-1");

        filter.doFilter(request, response, (req, res) -> current.set(RequestIdFilter.current().orElseThrow()));

        assertThat(current.get()).isEqualTo("trace-1");
    }
}
