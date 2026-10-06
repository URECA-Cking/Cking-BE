package kr.co.cking.auth.security.password;

import kr.co.cking.auth.application.port.AdminPasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt 알고리즘으로 관리자 비밀번호를 해시한다. */
@Component
public class BCryptAdminPasswordHasher implements AdminPasswordHasher {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 관리자 비밀번호 원문을 BCrypt 해시로 변환한다. */
    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
