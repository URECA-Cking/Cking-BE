package kr.co.cking.subscriptionverification.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelService;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelUpsertResult;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CreatorYoutubeChannelController.class, PublicCreatorYoutubeChannelController.class})
class CreatorYoutubeChannelControllerTest {

    private static final String REQUEST = """
            {
              "channelName": "예상치 못한 필름",
              "channelHandle": "@UnexpectedFilm"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorYoutubeChannelService channelService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 공개_채널은_JWT_없이_조회한다() throws Exception {
        given(channelService.getPublic(42L)).willReturn(channel());

        mockMvc.perform(get("/api/creators/{creatorId}/youtube-channel", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.creatorId").value(42))
                .andExpect(jsonPath("$.data.channelHandle").value("@unexpectedfilm"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 공개_조회_creatorId는_양수여야_한다(long creatorId) throws Exception {
        mockMvc.perform(get("/api/creators/{creatorId}/youtube-channel", creatorId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(channelService).should(never()).getPublic(any());
    }

    @Test
    void 내_채널_조회는_JWT가_필요하다() throws Exception {
        mockMvc.perform(get("/api/creator/youtube-channel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_채널은_JWT_memberId로_조회한다() throws Exception {
        given(channelService.getMine(7L)).willReturn(channel());

        mockMvc.perform(get("/api/creator/youtube-channel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channelName").value("예상치 못한 필름"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 최초_채널_설정은_201을_반환한다() throws Exception {
        given(channelService.put(eq(7L), any()))
                .willReturn(new CreatorYoutubeChannelUpsertResult(channel(), true));

        mockMvc.perform(put("/api/creator/youtube-channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.channelUrl")
                        .value("https://www.youtube.com/@unexpectedfilm"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 수정과_멱등_재적용은_200을_반환한다() throws Exception {
        given(channelService.put(eq(7L), any()))
                .willReturn(new CreatorYoutubeChannelUpsertResult(channel(), false));

        mockMvc.perform(put("/api/creator/youtube-channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 빈_채널명은_VALIDATION_FAILED다() throws Exception {
        mockMvc.perform(put("/api/creator/youtube-channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelName\":\" \",\"channelHandle\":\"@sample\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(channelService).should(never()).put(any(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 처리_중인_채널_수정은_409이다() throws Exception {
        given(channelService.put(eq(7L), any()))
                .willThrow(new BusinessException(SubscriptionVerificationErrorCode.INVALID_STATE));

        mockMvc.perform(put("/api/creator/youtube-channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    private CreatorYoutubeChannel channel() {
        return new CreatorYoutubeChannel(
                42L, "예상치 못한 필름", "@unexpectedfilm", Instant.parse("2026-09-28T03:00:00Z"));
    }
}
