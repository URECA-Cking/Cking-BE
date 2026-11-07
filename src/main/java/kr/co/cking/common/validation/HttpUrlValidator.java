package kr.co.cking.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/** 공백·null은 선택 필드의 미입력으로 허용하고, 값이 있으면 HTTP(S) 절대 URL만 허용한다. */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!value.equals(value.trim())) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return uri.isAbsolute()
                    && ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))
                    && hasHost(uri);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /** URI의 getHost()는 한글·밑줄 도메인을 null로 처리하므로, 파싱된 authority에서 호스트 부분을 확인한다. */
    private boolean hasHost(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return false;
        }
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        return !hostAndPort.isBlank() && !hostAndPort.startsWith(":");
    }
}
