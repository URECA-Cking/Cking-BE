package kr.co.cking.creator.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorSpaceCustomSlugTest {

    @ParameterizedTest
    @ValueSource(strings = {"iu-official", "iu_2024", "abc", "a1b", "creator-43"})
    void acceptsLowercaseAsciiSlug(String slug) {
        assertThat(CreatorSpaceCustomSlug.isValidFormat(slug)).isTrue();
    }

    /** 대문자·한글·전각 문자는 ai_ci collation에서 다른 값과 같게 비교될 수 있어 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "ab", "IU-official", "아이유", "iu.official", "iu official", "-iu", "iu-", "_iu", "iu_", "iu１", ""
    })
    void rejectsInvalidFormat(String slug) {
        assertThat(CreatorSpaceCustomSlug.isValidFormat(slug)).isFalse();
    }

    @Test
    void acceptsUpToThirtyChars() {
        assertThat(CreatorSpaceCustomSlug.isValidFormat("a".repeat(30))).isTrue();
        assertThat(CreatorSpaceCustomSlug.isValidFormat("a".repeat(31))).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(CreatorSpaceCustomSlug.isValidFormat(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "api", "creator", "space", "me", "cking", "official"})
    void detectsReservedWords(String slug) {
        assertThat(CreatorSpaceCustomSlug.isReserved(slug)).isTrue();
    }

    @Test
    void ordinarySlugIsNotReserved() {
        assertThat(CreatorSpaceCustomSlug.isReserved("iu-official")).isFalse();
    }
}
