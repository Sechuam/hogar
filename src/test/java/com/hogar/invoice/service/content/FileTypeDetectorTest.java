package com.hogar.invoice.service.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FileTypeDetectorTest {

    static final byte[] PDF = concat(ascii("%PDF-1.7\n%âãÏÓ\n"), ascii("1 0 obj"));
    static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00);
    static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R');
    static final byte[] WEBP = concat(ascii("RIFF"), bytes(0x24, 0x00, 0x00, 0x00), ascii("WEBPVP8 "));

    static Stream<Arguments> validFiles() {
        return Stream.of(
                Arguments.of(PDF, FileType.PDF, "application/pdf", "pdf"),
                Arguments.of(JPEG, FileType.JPEG, "image/jpeg", "jpg"),
                Arguments.of(PNG, FileType.PNG, "image/png", "png"),
                Arguments.of(WEBP, FileType.WEBP, "image/webp", "webp"));
    }

    @ParameterizedTest
    @MethodSource("validFiles")
    void detectsEachSupportedType(byte[] content, FileType expected, String contentType, String extension) {
        assertThat(FileTypeDetector.detect(content)).contains(expected);
        assertThat(expected.contentType()).isEqualTo(contentType);
        assertThat(expected.extension()).isEqualTo(extension);
    }

    @ParameterizedTest
    @MethodSource("validFiles")
    void exactSignatureIsEnoughButOneByteLessIsNot(byte[] content, FileType expected, String ct, String ext) {
        int signatureLength = switch (expected) {
            case PDF -> 5;
            case JPEG -> 3;
            case PNG -> 8;
            case WEBP -> 12;
        };
        assertThat(FileTypeDetector.detect(Arrays.copyOf(content, signatureLength))).contains(expected);
        assertThat(FileTypeDetector.detect(Arrays.copyOf(content, signatureLength - 1))).isEmpty();
    }

    @Test
    void riffWithoutWebpIsRejected() {
        byte[] wav = concat(ascii("RIFF"), bytes(0x24, 0, 0, 0), ascii("WAVEfmt "));
        byte[] avi = concat(ascii("RIFF"), bytes(0x24, 0, 0, 0), ascii("AVI LIST"));
        assertThat(FileTypeDetector.detect(wav)).isEmpty();
        assertThat(FileTypeDetector.detect(avi)).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("RIFF"))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("RIFF\0\0\0\0WEB"))).isEmpty();
    }

    @Test
    void webpMarkerOutsideOffsetEightIsRejected() {
        assertThat(FileTypeDetector.detect(ascii("RIFF\0\0\0WEBP\0"))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("XRIFF\0\0\0WEBP"))).isEmpty();
    }

    @Test
    void windowsExecutableRenamedToPdfIsRejected() {
        byte[] exe = concat(bytes('M', 'Z', 0x90, 0x00, 0x03, 0x00, 0x00, 0x00), ascii("This program cannot be run in DOS mode"));
        assertThat(FileTypeDetector.detect(exe)).isEmpty();
    }

    @Test
    void htmlSvgAndScriptsAreRejected() {
        assertThat(FileTypeDetector.detect(ascii("<!DOCTYPE html><html><script>alert(1)</script>"))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("<svg onload=alert(1)>"))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("#!/bin/sh\nrm -rf /"))).isEmpty();
    }

    @Test
    void pdfSignatureMustBeAtTheStart() {
        // PDF con basura delante (los lectores lo toleran; nosotros no) y en minúsculas.
        assertThat(FileTypeDetector.detect(ascii(" %PDF-1.4"))).isEmpty();
        assertThat(FileTypeDetector.detect(concat(bytes(0xEF, 0xBB, 0xBF), ascii("%PDF-1.4")))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("%pdf-1.4"))).isEmpty();
        assertThat(FileTypeDetector.detect(ascii("%PDF1.4"))).isEmpty();
    }

    @Test
    void otherImageFormatsAreRejected() {
        assertThat(FileTypeDetector.detect(ascii("GIF89a\1\0\1\0"))).isEmpty();
        assertThat(FileTypeDetector.detect(bytes('B', 'M', 0x36, 0, 0, 0))).isEmpty();
        assertThat(FileTypeDetector.detect(bytes(0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'))).isEmpty();
        assertThat(FileTypeDetector.detect(bytes('P', 'K', 0x03, 0x04))).isEmpty();
        assertThat(FileTypeDetector.detect(bytes(0xFF, 0xD8))).isEmpty();
    }

    @Test
    void emptyOrNullHeaderIsRejected() {
        assertThat(FileTypeDetector.detect(new byte[0])).isEmpty();
        assertThat(FileTypeDetector.detect((byte[]) null)).isEmpty();
    }

    @Test
    void detectFromStreamDoesNotConsumeIt() throws IOException {
        InputStream in = new BufferedInputStream(new ByteArrayInputStream(PNG));

        assertThat(FileTypeDetector.detect(in)).contains(FileType.PNG);
        assertThat(in.readAllBytes()).isEqualTo(PNG);
    }

    @Test
    void detectFromShortOrEmptyStream() throws IOException {
        assertThat(FileTypeDetector.detect(new BufferedInputStream(new ByteArrayInputStream(bytes(0xFF, 0xD8, 0xFF)))))
                .contains(FileType.JPEG);
        InputStream empty = new BufferedInputStream(new ByteArrayInputStream(new byte[0]));
        assertThat(FileTypeDetector.detect(empty)).isEmpty();
        assertThat(empty.read()).isEqualTo(-1);
    }

    @Test
    void detectFromStreamRequiresMarkSupport() {
        InputStream noMark = new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };
        assertThatThrownBy(() -> FileTypeDetector.detect(noMark)).isInstanceOf(IllegalArgumentException.class);
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(p -> p.length).sum();
        byte[] result = new byte[length];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, pos, part.length);
            pos += part.length;
        }
        return result;
    }
}
