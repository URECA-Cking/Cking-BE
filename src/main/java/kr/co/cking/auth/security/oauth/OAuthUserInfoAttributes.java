package kr.co.cking.auth.security.oauth;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider 원본 attributes에서 필수 Identity와 선택 프로필 값을 안전하게 읽는다. */
final class OAuthUserInfoAttributes {

    private OAuthUserInfoAttributes() {
    }

    static String requiredString(Map<String, Object> attributes, String field) {
        Object value = requireAttributes(attributes).get(field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있지 않은 문자열이어야 합니다.");
        }
        return stringValue;
    }

    static String requiredPositiveIntegerIdentifier(Map<String, Object> attributes, String field) {
        Object value = requireAttributes(attributes).get(field);
        if (value instanceof String stringValue && stringValue.matches("[1-9][0-9]*")) {
            return stringValue;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long numericValue = ((Number) value).longValue();
            if (numericValue > 0) {
                return Long.toString(numericValue);
            }
        }
        if (value instanceof BigInteger numericValue && numericValue.signum() > 0) {
            return numericValue.toString();
        }
        throw new IllegalArgumentException(field + "는 양의 정수 식별자여야 합니다.");
    }

    static String optionalString(Map<String, Object> attributes, String field) {
        Object value = requireAttributes(attributes).get(field);
        return value instanceof String stringValue ? stringValue : null;
    }

    static Map<String, Object> optionalMap(Map<String, Object> attributes, String field) {
        Object value = requireAttributes(attributes).get(field);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + "는 객체여야 합니다.");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                normalized.put(key, entry.getValue());
            }
        }
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private static Map<String, Object> requireAttributes(Map<String, Object> attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("OAuth attributes는 필수입니다.");
        }
        return attributes;
    }
}
