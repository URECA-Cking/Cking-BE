package kr.co.cking.member;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.config.WebMvcConfig;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.CurrentMemberIdArgumentResolver;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.application.MemberProfile;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.presentation.MemberController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebMvcConfig.class, CurrentMemberIdArgumentResolver.class})
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberQueryService memberQueryService;

    @MockitoBean
    private CreatorQueryService creatorQueryService;

    @BeforeEach
    void authenticatedMember() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none").subject("1").claim("role", "USER").build(), java.util.List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 일반_사용자의_현재_프로필을_공통_응답으로_반환한다() throws Exception {
        when(memberQueryService.getProfile(1L))
                .thenReturn(new MemberProfile(1L, "홍길동", "hong@example.com", MemberRole.USER));
        when(creatorQueryService.isCreatorMember(1L)).thenReturn(false);

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.memberId").value(1))
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.email").value("hong@example.com"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.creator").value(false));
    }

    @Test
    void Creator의_현재_프로필은_creator_true를_반환한다() throws Exception {
        when(memberQueryService.getProfile(1L))
                .thenReturn(new MemberProfile(1L, "크리에이터", "creator@example.com", MemberRole.USER));
        when(creatorQueryService.isCreatorMember(1L)).thenReturn(true);

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creator").value(true));
    }

    @Test
    void JWT_호출자_Member가_없으면_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        when(memberQueryService.getProfile(1L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
