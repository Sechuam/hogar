package com.hogar.household.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvitationCodeGeneratorTest {

    @Test
    void generatesTenCrockfordCharacters() {
        InvitationCodeGenerator generator = new InvitationCodeGenerator();

        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).matches("[0-9A-HJKMNP-TV-Z]{10}");
        }
    }

    @Test
    void alphabetHas32SymbolsWithoutAmbiguousLetters() {
        assertThat(InvitationCodeGenerator.ALPHABET).hasSize(32).doesNotContain("I", "L", "O", "U");
        assertThat(InvitationCodeGenerator.ALPHABET.chars().distinct().count()).isEqualTo(32);
    }

    @Test
    void usesEveryAlphabetSymbol() {
        InvitationCodeGenerator generator = new InvitationCodeGenerator(new Random(42));
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            generator.generate().chars().forEach(c -> seen.add((char) c));
        }

        assertThat(seen).hasSize(32);
    }

    @Test
    void codesDoNotRepeat() {
        InvitationCodeGenerator generator = new InvitationCodeGenerator();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(10_000);
    }

    @Test
    void normalizesUserInput() {
        assertThat(InvitationCodeGenerator.normalize("  abcd-efgh-jk ")).isEqualTo("ABCDEFGHJK");
        assertThat(InvitationCodeGenerator.normalize("oOiIlL")).isEqualTo("001111");
        assertThat(InvitationCodeGenerator.normalize(null)).isEmpty();
    }
}
