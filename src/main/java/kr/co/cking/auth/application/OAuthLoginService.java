package kr.co.cking.auth.application;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.repository.OAuthAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** OAuth Identity를 Cking Member에 연결하고 Login Code 발급 경계에 memberId를 제공한다. */
@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    private static final String DEFAULT_MEMBER_NAME = "사용자";
    private static final int MEMBER_NAME_MAX_CODE_POINTS = 50;

    private final OAuthAccountRepository oauthAccountRepository;
    private final OAuthAccountCreationService oauthAccountCreationService;

    public Long login(OAuthUserInfo userInfo) {
        return findMemberId(userInfo)
                .orElseGet(() -> createOrFindExisting(userInfo));
    }

    private java.util.Optional<Long> findMemberId(OAuthUserInfo userInfo) {
        return oauthAccountRepository.findByProviderAndProviderUserId(userInfo.provider(), userInfo.providerUserId())
                .map(OAuthAccount::getMemberId);
    }

    private Long createOrFindExisting(OAuthUserInfo userInfo) {
        try {
            return oauthAccountCreationService.create(userInfo, normalizeName(userInfo.name()));
        } catch (DataIntegrityViolationException exception) {
            // 동일 OAuth Identity의 동시 최초 로그인만 기존 계정 반환으로 복구한다.
            return findMemberId(userInfo).orElseThrow(() -> exception);
        }
    }

    static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_MEMBER_NAME;
        }

        String strippedName = name.strip();
        if (strippedName.isEmpty()) {
            return DEFAULT_MEMBER_NAME;
        }

        int codePointCount = strippedName.codePointCount(0, strippedName.length());
        if (codePointCount <= MEMBER_NAME_MAX_CODE_POINTS) {
            return strippedName;
        }
        return strippedName.substring(0, strippedName.offsetByCodePoints(0, MEMBER_NAME_MAX_CODE_POINTS));
    }
}
