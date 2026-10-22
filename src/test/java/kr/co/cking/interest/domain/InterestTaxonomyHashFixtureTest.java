package kr.co.cking.interest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import org.junit.jupiter.api.Test;

/**
 * Cking-LLM-Benchmark의 언어 공통 fixture(`tests/fixtures/taxonomy/`, 커밋 b6bef4f)를 그대로 복사해
 * Python과 같은 canonical JSON 바이트·SHA-256이 나오는지 확인한다. CRLF·CR·BOM·분해형 한글·NBSP·EM SPACE·따옴표를
 * 포함한 원본(`input.csv`)을 쓰므로 fixture는 `.gitattributes`에서 `-text`로 고정한다.
 */
class InterestTaxonomyHashFixtureTest {

    private static final Path DIR = Path.of("src/test/resources/fixtures/taxonomy");

    @Test
    void 원본_CSV에서_canonical_JSON_바이트와_해시가_LLM_fixture와_같다() throws IOException {
        List<Row> rows = rows(parseCsv(Files.readString(DIR.resolve("input.csv"), StandardCharsets.UTF_8)));

        assertThat(InterestTaxonomyHash.canonicalJson(rows).getBytes(StandardCharsets.UTF_8))
                .isEqualTo(Files.readAllBytes(DIR.resolve("canonical.json")));
        assertThat(InterestTaxonomyHash.compute(rows)).isEqualTo(read("sha256.txt"));
    }

    @Test
    void v02_17개_분야의_canonical_JSON과_해시가_LLM_fixture와_같다() throws IOException {
        // v02.canonical.json의 값을 그대로 행으로 되돌리면 같은 바이트가 다시 나와야 한다(정규화는 멱등).
        List<Row> rows = rowsFromCanonical(Files.readString(DIR.resolve("v02.canonical.json"), StandardCharsets.UTF_8));

        assertThat(rows).hasSize(17);
        assertThat(InterestTaxonomyHash.canonicalJson(rows).getBytes(StandardCharsets.UTF_8))
                .isEqualTo(Files.readAllBytes(DIR.resolve("v02.canonical.json")));
        assertThat(InterestTaxonomyHash.compute(rows)).isEqualTo(read("v02.sha256.txt"));
    }

    private String read(String name) throws IOException {
        return Files.readString(DIR.resolve(name), StandardCharsets.UTF_8).strip();
    }

    private List<Row> rows(List<List<String>> records) {
        List<Row> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) { // 헤더 제외
            rows.add(new Row(record.get(0), record.get(1), record.get(2)));
        }
        return rows;
    }

    private List<Row> rowsFromCanonical(String json) throws IOException {
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        List<Row> rows = new ArrayList<>();
        tree.get("categories").forEach(node -> rows.add(new Row(
                node.get("code").asText(), node.get("name").asText(), node.get("description").asText())));
        return rows;
    }

    /** RFC 4180 최소 파서(테스트 전용): BOM 무시, 따옴표 필드 안의 줄바꿈·쉼표·"" 이스케이프를 지원한다. */
    private List<List<String>> parseCsv(String text) {
        String input = text.startsWith("﻿") ? text.substring(1) : text;
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < input.length(); index++) {
            char ch = input.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < input.length() && input.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && index + 1 < input.length() && input.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
