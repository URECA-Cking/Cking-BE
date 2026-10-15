package kr.co.cking.interest.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 세부 주제 세트의 canonical 해시다. 정규화·이스케이프·SHA-256 규칙은 {@link InterestTaxonomyHash}와 같고
 * 행 형태만 {@code {"subtopics":[{"code","parent","name","description"}]}}다.
 */
public final class InterestSubtopicHash {

    private InterestSubtopicHash() {
    }

    /** 해시 계산 대상 한 행이다. 행 순서가 해시에 포함되므로 호출자는 {@code display_order} 순으로 넘긴다. */
    public record Row(String code, String parent, String name, String description) {
    }

    public static String compute(List<Row> rows) {
        return InterestTaxonomyHash.sha256Hex(canonicalJson(rows).getBytes(StandardCharsets.UTF_8));
    }

    static String canonicalJson(List<Row> rows) {
        StringBuilder json = new StringBuilder("{\"subtopics\":[");
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (index > 0) {
                json.append(',');
            }
            appendField(json, "{\"code\":", row.code());
            appendField(json, ",\"parent\":", row.parent());
            appendField(json, ",\"name\":", row.name());
            appendField(json, ",\"description\":", row.description());
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private static void appendField(StringBuilder json, String key, String value) {
        json.append(key);
        InterestTaxonomyHash.appendString(json, InterestTaxonomyHash.normalize(value));
    }
}
