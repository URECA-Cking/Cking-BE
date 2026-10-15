package kr.co.cking.interest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kr.co.cking.interest.domain.InterestSubtopicHash.Row;
import org.junit.jupiter.api.Test;

/**
 * 세부 주제 85개(`subtopic-v1`)의 canonical JSON 바이트와 SHA-256이 Cking-LLM-Benchmark의 같은 규칙 fixture와 같은지
 * 확인한다. fixture는 Python이 `data/creator-subtopics.json`에서 같은 정규화 규칙으로 만든 값이다.
 */
class InterestSubtopicHashFixtureTest {

    private static final Path DIR = Path.of("src/test/resources/fixtures/taxonomy");

    @Test
    void 세부_주제_85개의_canonical_JSON과_해시가_fixture와_같다() throws IOException {
        byte[] canonical = Files.readAllBytes(DIR.resolve("subtopic-v1.canonical.json"));
        List<Row> rows = new ArrayList<>();
        new ObjectMapper().readTree(canonical).get("subtopics").forEach(node -> rows.add(new Row(
                node.get("code").asText(), node.get("parent").asText(),
                node.get("name").asText(), node.get("description").asText())));

        assertThat(rows).hasSize(85);
        assertThat(InterestSubtopicHash.canonicalJson(rows).getBytes(StandardCharsets.UTF_8)).isEqualTo(canonical);
        assertThat(InterestSubtopicHash.compute(rows)).isEqualTo(
                Files.readString(DIR.resolve("subtopic-v1.sha256.txt"), StandardCharsets.UTF_8).strip());
    }

    @Test
    void 앞뒤_ASCII_공백과_CRLF는_정규화하고_NBSP는_보존한다() {
        String plain = InterestSubtopicHash.compute(List.of(new Row("C", "P", "n", "d")));

        assertThat(InterestSubtopicHash.compute(List.of(new Row(" C ", "P", "n\r\n", "\td")))).isEqualTo(plain);
        assertThat(InterestSubtopicHash.compute(List.of(new Row("C", "P", "n", "d\u00a0")))).isNotEqualTo(plain);
        assertThat(InterestSubtopicHash.canonicalJson(List.of(new Row("C", "P", "n", "d"))))
                .isEqualTo("{\"subtopics\":[{\"code\":\"C\",\"parent\":\"P\",\"name\":\"n\",\"description\":\"d\"}]}");
    }
}
