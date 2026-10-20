package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StreamConsumerNamesTest {

    @Test
    void 서버_식별자가_있으면_접두사에_붙인다() {
        StreamConsumerNames names = new StreamConsumerNames(" i-0abc123 ");

        assertThat(names.of("spend")).isEqualTo("spend-i-0abc123");
        assertThat(names.of("common-earn")).isEqualTo("common-earn-i-0abc123");
    }

    @Test
    void 소비자를_여러_개_두면_서버_이름_뒤에_번호를_붙인다() {
        StreamConsumerNames names = new StreamConsumerNames("i-0abc123");

        assertThat(names.of("spend", 3)).containsExactly("spend-i-0abc123-0", "spend-i-0abc123-1", "spend-i-0abc123-2");
    }

    @Test
    void 서버_식별자가_없으면_호스트_이름을_쓴다() {
        StreamConsumerNames names = new StreamConsumerNames("");

        assertThat(names.of("spend")).startsWith("spend-").hasSizeGreaterThan("spend-".length());
    }
}
