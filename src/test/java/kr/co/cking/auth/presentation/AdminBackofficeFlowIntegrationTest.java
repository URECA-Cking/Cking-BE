package kr.co.cking.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.co.cking.auth.application.port.AdminPasswordHasher;
import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.auth.repository.AdminAccountRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 관리자 백오피스의 인증·CORS·운영 목록 진입점을 실제 Spring 구성으로 연결 검증한다. */
@SpringBootTest(properties = {
        "cking.cors.allowed-origins=https://dev.cking.co.kr,https://dev-admin.cking.co.kr",
        "cking.cors.allow-credentials=true",
        "cking.auth.refresh.user-allowed-origins=https://dev.cking.co.kr",
        "cking.auth.refresh.admin-allowed-origins=https://dev-admin.cking.co.kr"
})
@AutoConfigureMockMvc
class AdminBackofficeFlowIntegrationTest {

    private static final String ADMIN_WEB_ORIGIN = "https://dev-admin.cking.co.kr";
    private static final String USER_WEB_ORIGIN = "https://dev.cking.co.kr";
    private static final Pattern ADMIN_REFRESH_COOKIE = Pattern.compile("admin_refresh_token=([^;]+)");

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private MemberRepository memberRepository;
    @Autowired private AdminAccountRepository adminAccountRepository;
    @Autowired private AdminPasswordHasher adminPasswordHasher;

    private AdminFixture createdAdminFixture;

    /** 테스트가 만든 관리자 계정과 Member를 제거해 다음 실행의 운영 목록 검증을 독립적으로 유지한다. */
    @AfterEach
    void cleanUp() {
        if (createdAdminFixture != null) {
            adminAccountRepository.deleteById(createdAdminFixture.adminAccountId());
            memberRepository.deleteById(createdAdminFixture.memberId());
        }
    }

    /** 관리자 로그인부터 운영 목록 접근·Refresh·Logout 뒤 재사용 거절까지 하나의 브라우저 흐름으로 검증한다. */
    @Test
    void 관리자_백오피스_인증과_운영_목록_흐름을_검증한다() throws Exception {
        AdminFixture fixture = createAdminFixture();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/admin/login")
                        .header(ORIGIN, ADMIN_WEB_ORIGIN)
                        .contentType("application/json")
                        .content("{\"loginId\":\"%s\",\"password\":\"%s\"}"
                                .formatted(fixture.loginId(), fixture.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn();

        String accessToken = accessToken(loginResult);
        String refreshToken = adminRefreshToken(loginResult);
        assertAdminToken(accessToken, fixture.memberId());

        mockMvc.perform(get("/api/me").header(AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(fixture.memberId()))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        mockMvc.perform(get("/api/admin/events").header(AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        mockMvc.perform(get("/api/admin/redraw-requests").header(AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        MvcResult refreshResult = mockMvc.perform(post("/api/admin/auth/refresh")
                        .header(ORIGIN, ADMIN_WEB_ORIGIN)
                        .cookie(new Cookie("admin_refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn();
        String rotatedRefreshToken = adminRefreshToken(refreshResult);
        assertAdminToken(accessToken(refreshResult), fixture.memberId());

        mockMvc.perform(post("/api/admin/auth/logout")
                        .header(ORIGIN, ADMIN_WEB_ORIGIN)
                        .cookie(new Cookie("admin_refresh_token", rotatedRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        mockMvc.perform(post("/api/admin/auth/refresh")
                        .header(ORIGIN, ADMIN_WEB_ORIGIN)
                        .cookie(new Cookie("admin_refresh_token", rotatedRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    /** 사용자·관리자 Web의 세션별 CORS 경계와 등록되지 않은 Origin 차단을 검증한다. */
    @Test
    void Web별_인증_CORS_경계를_검증한다() throws Exception {
        mockMvc.perform(options("/api/auth/refresh")
                        .header(ORIGIN, USER_WEB_ORIGIN)
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.CONTENT_TYPE))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, USER_WEB_ORIGIN))
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mockMvc.perform(options("/api/auth/admin/login")
                        .header(ORIGIN, ADMIN_WEB_ORIGIN)
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.CONTENT_TYPE))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, ADMIN_WEB_ORIGIN))
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mockMvc.perform(options("/api/admin/auth/refresh")
                        .header(ORIGIN, USER_WEB_ORIGIN)
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.CONTENT_TYPE))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(options("/api/auth/admin/login")
                        .header(ORIGIN, "https://unknown.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.CONTENT_TYPE))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** 실행마다 고유한 활성 ADMIN Member와 BCrypt 관리자 계정을 만든다. */
    private AdminFixture createAdminFixture() {
        String suffix = UUID.randomUUID().toString();
        String password = "admin-integration-password";
        Member member = memberRepository.save(new Member("관리자 테스트", null, "admin-" + suffix + "@example.com", MemberRole.ADMIN));
        String loginId = "admin-" + suffix;
        AdminAccount adminAccount = adminAccountRepository.save(
                new AdminAccount(member.getMemberId(), loginId, adminPasswordHasher.hash(password)));
        createdAdminFixture = new AdminFixture(member.getMemberId(), adminAccount.getAdminAccountId(), loginId, password);
        return createdAdminFixture;
    }

    /** 로그인·갱신 응답 공통 봉투에서 Access JWT 원문만 꺼낸다. */
    private String accessToken(MvcResult result) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return data.path("accessToken").asText();
    }

    /** Set-Cookie 헤더에서 관리자 전용 Refresh Cookie 값을 추출한다. */
    private String adminRefreshToken(MvcResult result) {
        Matcher matcher = ADMIN_REFRESH_COOKIE.matcher(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** 발급된 JWT가 테스트 관리자 Member와 ADMIN 역할을 유지하는지 확인한다. */
    private void assertAdminToken(String accessToken, Long memberId) {
        var jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getSubject()).isEqualTo(memberId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    /** Authorization 헤더에 넣을 Bearer Access JWT 값을 만든다. */
    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    /** 테스트 관리자 계정의 식별자와 로그에 남기지 않는 자격 증명을 보관한다. */
    private record AdminFixture(Long memberId, Long adminAccountId, String loginId, String password) {
    }
}
