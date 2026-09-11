package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("CorrelationIds tests (DD-43183 Story 1)")
class CorrelationIdsTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("AC-001: canonical header alone resolves as the correlation ID")
    void resolvesCanonicalHeaderAlone() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("abc-123");

        assertThat(CorrelationIds.resolveInbound(request)).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("AC-002: alias header alone is honoured as the correlation ID")
    void resolvesAliasHeaderAlone() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn("xyz-789");

        assertThat(CorrelationIds.resolveInbound(request)).isEqualTo("xyz-789");
    }

    @Test
    @DisplayName("AC-003(a): both headers present, different values — canonical wins, alias appears nowhere")
    void canonicalWinsWhenBothPresentWithDifferentValues() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("canonical-value");
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn("alias-value");

        assertThat(CorrelationIds.resolveInbound(request)).isEqualTo("canonical-value");
    }

    @Test
    @DisplayName("AC-003(b): canonical header blank falls through to a usable alias — "
            + "a blank canonical value must not shadow a usable alias into a generated ID")
    void blankCanonicalFallsThroughToAlias() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("   ");
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn("alias-value");

        assertThat(CorrelationIds.resolveInbound(request)).isEqualTo("alias-value");
    }

    @Test
    @DisplayName("AC-003(b): canonical header rejected by validation falls through to a usable alias")
    void rejectedCanonicalFallsThroughToAlias() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("bad value with spaces");
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn("alias-value");

        assertThat(CorrelationIds.resolveInbound(request)).isEqualTo("alias-value");
    }

    @Test
    @DisplayName("AC-003(c): neither header yields a usable value — a fresh non-blank value is generated")
    void generatesWhenNeitherHeaderYieldsAUsableValue() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn(null);
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn(null);

        final String resolved = CorrelationIds.resolveInbound(request);

        assertThat(resolved).isNotBlank();
    }

    @Test
    @DisplayName("AC-004: a blank header is treated as absent, not as a usable empty value")
    void blankHeaderIsTreatedAsAbsent() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("");
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn("   ");

        assertThat(CorrelationIds.resolveInbound(request)).isNotBlank();
    }

    @Test
    @DisplayName("AC-010: a value over 64 characters is rejected, not truncated")
    void rejectsOverlongValue() {
        final String tooLong = "a".repeat(65);

        assertThat(CorrelationIds.sanitise(tooLong)).isNull();
    }

    @Test
    @DisplayName("AC-010: a 64-character value is accepted (the boundary)")
    void acceptsExactlyMaxLength() {
        final String exactly64 = "a".repeat(64);

        assertThat(CorrelationIds.sanitise(exactly64)).isEqualTo(exactly64);
    }

    @Test
    @DisplayName("AC-010: a value containing an illegal character (CRLF) is rejected, never sanitised-in-place")
    void rejectsIllegalCharacters() {
        assertThat(CorrelationIds.sanitise("abc\r\ndef")).isNull();
        assertThat(CorrelationIds.sanitise("abc\"def")).isNull();
        assertThat(CorrelationIds.sanitise("abc def")).isNull();
    }

    @Test
    @DisplayName("AC-010: the allow-listed character set is accepted verbatim")
    void acceptsAllowListedCharacters() {
        final String value = "abc-123_ABC.def:ghi";

        assertThat(CorrelationIds.sanitise(value)).isEqualTo(value);
    }

    @Test
    @DisplayName("null and blank are treated as absent, not rejected-with-a-value")
    void nullAndBlankAreAbsent() {
        assertThat(CorrelationIds.sanitise(null)).isNull();
        assertThat(CorrelationIds.sanitise("")).isNull();
        assertThat(CorrelationIds.sanitise("   ")).isNull();
    }

    @Test
    @DisplayName("generate() always returns a non-blank value")
    void generateReturnsNonBlank() {
        assertThat(CorrelationIds.generate()).isNotBlank();
    }

    @Test
    @DisplayName("currentOrGenerate() returns the ambient MDC value when present and valid, and writes nothing new")
    void currentOrGenerateReturnsAmbientValue() {
        MDC.put(CorrelationIds.MDC_KEY, "ambient-value");

        assertThat(CorrelationIds.currentOrGenerate()).isEqualTo("ambient-value");
    }

    @Test
    @DisplayName("currentOrGenerate() generates and writes MDC when nothing valid is ambient")
    void currentOrGenerateGeneratesAndWritesWhenAbsent() {
        final String result = CorrelationIds.currentOrGenerate();

        assertThat(result).isNotBlank();
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo(result);
    }

    @Test
    @DisplayName("currentOrRandom() never writes MDC, even when it must generate")
    void currentOrRandomNeverWritesMdc() {
        final String result = CorrelationIds.currentOrRandom();

        assertThat(result).isNotBlank();
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("currentOrRandom() returns the ambient value when present, still without writing MDC")
    void currentOrRandomReturnsAmbientValueWithoutRewriting() {
        MDC.put(CorrelationIds.MDC_KEY, "ambient-value");

        assertThat(CorrelationIds.currentOrRandom()).isEqualTo("ambient-value");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("ambient-value");
    }
}
