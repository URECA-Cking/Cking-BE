package kr.co.cking.interest.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

/**
 * 관심 분야 분류체계의 canonical 해시다. Cking-LLM(Python)이 같은 규칙으로 계산한 값과 바이트 단위로 같아야 한다.
 *
 * <ol>
 *   <li>각 필드의 줄바꿈 {@code CRLF}·{@code CR}을 {@code LF}로 바꾼다.
 *   <li>앞뒤에서 ASCII 공백·탭·LF만 제거한다(NBSP·전각 공백은 제거하지 않는다). 내부 공백은 보존한다.
 *   <li>Unicode NFC로 정규화한다.
 *   <li>행 순서를 유지해 {@code {"categories":[{"code","name","description"}]}}를 공백 없는 JSON으로 만든다.
 *   <li>한글을 이스케이프하지 않고 UTF-8(BOM·끝 줄바꿈 없음) 바이트의 SHA-256을 소문자 hex로 낸다.
 * </ol>
 *
 * {@code String.trim()}·{@code strip()}과 Jackson 직렬화는 Python과 공백·이스케이프 범위가 달라 쓰지 않는다.
 * JSON 이스케이프는 Python {@code json.dumps(ensure_ascii=False)}와 같이 {@code "}, {@code \}, 0x20 미만 제어문자만 한다.
 */
public final class InterestTaxonomyHash {

    private InterestTaxonomyHash() {
    }

    /** 해시 계산 대상 한 행이다. 행 순서가 해시에 포함되므로 호출자는 {@code display_order} 순으로 넘긴다. */
    public record Row(String code, String name, String description) {
    }

    public static String compute(List<Row> rows) {
        return sha256Hex(canonicalJson(rows).getBytes(StandardCharsets.UTF_8));
    }

    static String canonicalJson(List<Row> rows) {
        StringBuilder json = new StringBuilder("{\"categories\":[");
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"code\":");
            appendString(json, normalize(row.code()));
            json.append(",\"name\":");
            appendString(json, normalize(row.name()));
            json.append(",\"description\":");
            appendString(json, normalize(row.description()));
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private static String normalize(String value) {
        String unified = value.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int end = unified.length();
        while (start < end && isTrimmed(unified.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(unified.charAt(end - 1))) {
            end--;
        }
        return Normalizer.normalize(unified.substring(start, end), Normalizer.Form.NFC);
    }

    private static boolean isTrimmed(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\n';
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
