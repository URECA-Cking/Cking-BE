package kr.co.cking.ticket.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;

import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;

/** 관리자 API에서 실제 인증, MySQL, Redis, 보정 경계를 함께 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
class TicketAdminRegressionIntegrationTest {

    private static final long ADMIN_ID = 987301L;
    private static final long MEMBER_ID = 987302L;
    private static final long CREATOR_OWNER_ID = 987303L;
    private static final long CREATOR_ID = 987401L;
    private static final String REASON = "TC-07 정합성 보정";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;

    /** 각 시나리오에 관리자, 대상 회원, Creator와 두 종류의 DB 잔액을 준비한다. */
    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", MEMBER_ID, "대상 회원", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", CREATOR_OWNER_ID, "크리에이터", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name, activity_name, activity_name_normalized, activity_name_configured) VALUES (?, ?, ?, (@ticket_admin_activity_name := CONCAT('c', SUBSTRING(MD5(UUID()), 1, 19))), @ticket_admin_activity_name, true)",
                CREATOR_ID, CREATOR_OWNER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO user_ticket_balance (member_id, creator_id, balance) VALUES (?, ?, ?)",
                MEMBER_ID, CREATOR_ID, 10L);
        jdbcTemplate.update("INSERT INTO user_common_ticket_balance (member_id, balance) VALUES (?, ?)", MEMBER_ID, 6L);
    }

    /** 테스트가 만든 원장, 잔액, 회원과 Redis 키를 제거한다. */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM common_ticket_ledger WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?", MEMBER_ID, CREATOR_ID);
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?)", ADMIN_ID, MEMBER_ID, CREATOR_OWNER_ID);
        jdbcTemplate.update("DELETE FROM dead_stream_message WHERE source_stream_id LIKE 'tc07-%'");
        redisTemplate.delete(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID));
        redisTemplate.delete(CommonTicketRedisKeys.balance(MEMBER_ID));
        redisTemplate.delete(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID));
        redisTemplate.delete(CommonTicketRedisKeys.maintenance(MEMBER_ID));
    }

    /** Creator와 공용 잔액의 일치, 불일치, Redis 키 부재, DB 행 부재를 HTTP 응답에서 검증한다. */
    @Test
    void 관리자_잔액_조회는_두_저장소의_관측값과_미존재를_구분한다() throws Exception {
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID), "10");
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(MEMBER_ID), "9");

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID))
                        .param("couponType", "CREATOR")
                        .param("creatorId", String.valueOf(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redisBalance").value(10))
                .andExpect(jsonPath("$.data.mysqlBalance").value(10))
                .andExpect(jsonPath("$.data.balanceDifference").value(0));

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID)).param("couponType", "COMMON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redisBalance").value(9))
                .andExpect(jsonPath("$.data.mysqlBalance").value(6))
                .andExpect(jsonPath("$.data.balanceDifference").value(3));

        redisTemplate.delete(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID));
        jdbcTemplate.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", MEMBER_ID);

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID))
                        .param("couponType", "CREATOR")
                        .param("creatorId", String.valueOf(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redisKeyExists").value(false))
                .andExpect(jsonPath("$.data.redisBalance").isEmpty())
                .andExpect(jsonPath("$.data.balanceDifference").isEmpty());

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID)).param("couponType", "COMMON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mysqlBalanceRowExists").value(false))
                .andExpect(jsonPath("$.data.mysqlBalance").isEmpty())
                .andExpect(jsonPath("$.data.balanceDifference").isEmpty());
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isFalse();
    }

    /** 두 원장의 실제 행이 관리자 이력 API에서 해당 회원과 종류로 분리되는지 검증한다. */
    @Test
    void 관리자_이력_조회는_Creator와_공용_Ledger를_분리한다() throws Exception {
        jdbcTemplate.update("INSERT INTO ticket_ledger (member_id, creator_id, delta_amount, type, reason, balance_before, balance_after) VALUES (?, ?, ?, 'COMPENSATE', ?, ?, ?)",
                MEMBER_ID, CREATOR_ID, 7L, REASON, 3L, 10L);
        jdbcTemplate.update("INSERT INTO common_ticket_ledger (member_id, delta_amount, type, reason, balance_before, balance_after) VALUES (?, ?, 'COMPENSATE', ?, ?, ?)",
                MEMBER_ID, -2L, REASON, 8L, 6L);

        mockMvc.perform(get("/api/admin/tickets/history")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID)).param("couponType", "CREATOR")
                        .param("creatorId", String.valueOf(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("COMPENSATE"))
                .andExpect(jsonPath("$.data.items[0].deltaAmount").value(7))
                .andExpect(jsonPath("$.data.items[0].reason").value(REASON));

        mockMvc.perform(get("/api/admin/tickets/history")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID)).param("couponType", "COMMON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("COMPENSATE"))
                .andExpect(jsonPath("$.data.items[0].deltaAmount").value(-2))
                .andExpect(jsonPath("$.data.creatorId").isEmpty());
    }

    /** 인증 누락, USER 토큰, ADMIN 클레임과 DB 역할 불일치를 모두 거절한다. */
    @Test
    void 관리자_API는_ADMIN_외_접근을_거부한다() throws Exception {
        mockMvc.perform(get("/api/admin/tickets/balance"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/tickets/history")
                        .header(AUTHORIZATION, bearer(MEMBER_ID, "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/tickets/balance")
                        .header(AUTHORIZATION, bearer(MEMBER_ID, "ADMIN"))
                        .param("memberId", String.valueOf(MEMBER_ID)).param("couponType", "COMMON"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 관리자 보정 API가 두 잔액을 DB 기준으로 복원하고 감사 Ledger를 남기는지 검증한다. */
    @Test
    void 관리자_보정은_Redis와_COMPENSATE_Ledger를_함께_갱신한다() throws Exception {
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID), "3");
        redisTemplate.opsForValue().set(CommonTicketRedisKeys.balance(MEMBER_ID), "2");

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":%d,\"creatorId\":%d,\"reason\":\"%s\"}"
                                .formatted(MEMBER_ID, CREATOR_ID, REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(10));
        mockMvc.perform(post("/api/admin/tickets/common/resync")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":%d,\"reason\":\"%s\"}".formatted(MEMBER_ID, REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(6));

        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("10");
        assertThat(redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(MEMBER_ID))).isEqualTo("6");
        assertThat(jdbcTemplate.queryForObject("SELECT delta_amount FROM ticket_ledger WHERE member_id = ? AND type = 'COMPENSATE'", Long.class, MEMBER_ID))
                .isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject("SELECT delta_amount FROM common_ticket_ledger WHERE member_id = ? AND type = 'COMPENSATE'", Long.class, MEMBER_ID))
                .isEqualTo(4L);
    }

    /** 이미 획득한 보정 락과 미해결 Stream 메시지는 잔액 덮어쓰기를 막는다. */
    @Test
    void 보정은_락_충돌과_미반영_메시지를_거부한다() throws Exception {
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID), "3");
        redisTemplate.opsForValue().set(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID), "other-token");

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":%d,\"creatorId\":%d,\"reason\":\"%s\"}"
                                .formatted(MEMBER_ID, CREATOR_ID, REASON)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_COMMAND"));
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID)))
                .isEqualTo("other-token");
        redisTemplate.delete(TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID));

        jdbcTemplate.update("INSERT INTO dead_stream_message (source_stream_id, stream_type, payload, retry_count, resolution_status) VALUES ('tc07-common-1', 'COMMON_EARN', ?, 1, 'UNRESOLVED')",
                "{\"userId\":\"%d\"}".formatted(MEMBER_ID));
        mockMvc.perform(post("/api/admin/tickets/common/resync")
                        .header(AUTHORIZATION, bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":%d,\"reason\":\"%s\"}".formatted(MEMBER_ID, REASON)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM common_ticket_ledger WHERE member_id = ?", Integer.class, MEMBER_ID))
                .isZero();
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID))).isEqualTo("3");
    }

    /** 테스트용 Member 역할에 맞는 서명된 Access JWT를 Bearer 헤더로 만든다. */
    private String bearer(long memberId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("cking").subject(String.valueOf(memberId))
                .claim("role", role).issuedAt(now).expiresAt(now.plusSeconds(1800)).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return "Bearer " + token;
    }
}
