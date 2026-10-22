package kr.co.cking.auth.security.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import org.springframework.stereotype.Component;

/** Spring Security registration ID에 맞는 Provider Mapper를 선택한다. */
@Component
public class OAuthUserInfoResolver {

    private final Map<String, OAuthUserInfoMapper> mapperByRegistrationId;

    public OAuthUserInfoResolver(List<OAuthUserInfoMapper> mappers) {
        Map<String, OAuthUserInfoMapper> resolved = new LinkedHashMap<>();
        for (OAuthUserInfoMapper mapper : mappers) {
            String registrationId = normalizeRegistrationId(mapper.registrationId());
            if (resolved.putIfAbsent(registrationId, mapper) != null) {
                throw new IllegalStateException("OAuth registration ID가 중복됩니다: " + registrationId);
            }
        }
        this.mapperByRegistrationId = Map.copyOf(resolved);
    }

    public OAuthUserInfo resolve(String registrationId, Map<String, Object> attributes) {
        String normalizedRegistrationId = normalizeRegistrationId(registrationId);
        OAuthUserInfoMapper mapper = mapperByRegistrationId.get(normalizedRegistrationId);
        if (mapper == null) {
            throw new IllegalArgumentException("지원하지 않는 OAuth Provider입니다: " + registrationId);
        }
        return mapper.map(attributes);
    }

    private static String normalizeRegistrationId(String registrationId) {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("OAuth registrationId는 필수입니다.");
        }
        return registrationId.toLowerCase(Locale.ROOT);
    }
}
