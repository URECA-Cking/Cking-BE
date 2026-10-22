package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.post.application.dto.PostLikeView;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static kr.co.cking.post.PostFixtures.creator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PostLikeServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long OWNER_MEMBER_ID = 10L;
    private static final Long MEMBER_ID = 20L;
    private static final Long POST_ID = 100L;
    private static final Instant DECIDED_AT = Instant.parse("2026-10-13T00:00:00Z");

    private final PostLikeWriter writer = mock(PostLikeWriter.class);
    private final MissionCompletionService missionCompletionService = mock(MissionCompletionService.class);
    private final PostLikeService service = new PostLikeService(writer, missionCompletionService);

    @BeforeEach
    void setUp() {
        given(writer.like(MEMBER_ID, CREATOR_ID, POST_ID))
                .willReturn(new PostLikeWriter.Result(creator(CREATOR_ID, OWNER_MEMBER_ID), true, DECIDED_AT, 3));
    }

    @Test
    void 좋아요가_성공하고_응모권_적립이_접수되면_missionAwarded는_true이다() {
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, DECIDED_AT)).willReturn(outcome(EarnResultCode.EARN_ACCEPTED));

        PostLikeView view = service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isTrue();
        assertThat(view.likeCount()).isEqualTo(3);
        assertThat(view.missionAwarded()).isTrue();
    }

    @Test
    void 이미_받은_보상은_ALREADY_PROCESSED라_missionAwarded가_false이고_좋아요는_성공이다() {
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, DECIDED_AT))
                .willReturn(outcome(EarnResultCode.ALREADY_PROCESSED));

        PostLikeView view = service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isTrue();
        assertThat(view.missionAwarded()).isFalse();
    }

    @Test
    void 좋아요_미션이_없거나_비활성이어도_좋아요는_성공하고_missionAwarded는_false이다() {
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, DECIDED_AT))
                .willThrow(new BusinessException(MissionErrorCode.MISSION_NOT_FOUND))
                .willThrow(new BusinessException(MissionErrorCode.MISSION_INACTIVE));

        PostLikeView noMission = service.like(MEMBER_ID, CREATOR_ID, POST_ID);
        PostLikeView inactive = service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(noMission.liked()).isTrue();
        assertThat(noMission.missionAwarded()).isFalse();
        assertThat(inactive.liked()).isTrue();
        assertThat(inactive.missionAwarded()).isFalse();
    }

    @Test
    void 보상_처리_장애는_좋아요를_실패시키지_않는다() {
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, DECIDED_AT))
                .willThrow(new BusinessException(MissionErrorCode.EARN_STATUS_UNKNOWN));

        PostLikeView view = service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isTrue();
        assertThat(view.missionAwarded()).isFalse();
    }

    @Test
    void 예상하지_못한_예외도_좋아요를_실패시키지_않는다() {
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, DECIDED_AT))
                .willThrow(new IllegalStateException("redis down"));

        PostLikeView view = service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isTrue();
        assertThat(view.missionAwarded()).isFalse();
    }

    @Test
    void 작성_Creator_본인의_좋아요는_미션_보상을_요청하지_않는다() {
        given(writer.like(OWNER_MEMBER_ID, CREATOR_ID, POST_ID))
                .willReturn(new PostLikeWriter.Result(creator(CREATOR_ID, OWNER_MEMBER_ID), true, DECIDED_AT, 1));

        PostLikeView view = service.like(OWNER_MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isTrue();
        assertThat(view.missionAwarded()).isFalse();
        verify(missionCompletionService, never()).completeByPostLike(anyLong(), anyLong(), any());
    }

    @Test
    void 오늘_누른_좋아요에_같은_요청이_다시_오면_보상을_다시_시도한다() {
        given(writer.like(MEMBER_ID, CREATOR_ID, POST_ID))
                .willReturn(new PostLikeWriter.Result(creator(CREATOR_ID, OWNER_MEMBER_ID), true, DECIDED_AT, 3));
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, DECIDED_AT)).willReturn(outcome(EarnResultCode.EARN_ACCEPTED));

        assertThat(service.like(MEMBER_ID, CREATOR_ID, POST_ID).missionAwarded()).isTrue();
    }

    /**
     * 자격 판정(Writer)과 보상 지급(Mission) 사이에 UTC 자정을 넘겨도 판정한 시각을 그대로 넘겨 두 날짜가 어긋나지 않는다.
     * Mission이 자기 시계를 다시 읽으면 D에 판정한 중복 요청이 D+1 보상으로 처리될 수 있다.
     */
    @Test
    void 자격을_판정한_시각을_그대로_미션_보상에_전달한다() {
        Instant justBeforeMidnight = Instant.parse("2026-10-13T23:59:59.999Z");
        given(writer.like(MEMBER_ID, CREATOR_ID, POST_ID))
                .willReturn(new PostLikeWriter.Result(creator(CREATOR_ID, OWNER_MEMBER_ID), true, justBeforeMidnight, 3));
        given(missionCompletionService.completeByPostLike(CREATOR_ID, MEMBER_ID, justBeforeMidnight))
                .willReturn(outcome(EarnResultCode.ALREADY_PROCESSED));

        service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        verify(missionCompletionService).completeByPostLike(CREATOR_ID, MEMBER_ID, justBeforeMidnight);
    }

    @Test
    void 이전_날에_누른_좋아요를_유지한_채_날짜만_바뀐_중복_요청은_보상을_요청하지_않는다() {
        given(writer.like(MEMBER_ID, CREATOR_ID, POST_ID))
                .willReturn(new PostLikeWriter.Result(creator(CREATOR_ID, OWNER_MEMBER_ID), false, DECIDED_AT, 3));

        PostLikeView view = service.like(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isTrue();
        assertThat(view.likeCount()).isEqualTo(3);
        assertThat(view.missionAwarded()).isFalse();
        verify(missionCompletionService, never()).completeByPostLike(anyLong(), anyLong(), any());
    }

    @Test
    void 취소는_미션을_호출하지_않고_회수하지_않는다() {
        given(writer.unlike(MEMBER_ID, CREATOR_ID, POST_ID))
                .willReturn(new PostLikeWriter.Result(creator(CREATOR_ID, OWNER_MEMBER_ID), false, DECIDED_AT, 2));

        PostLikeView view = service.unlike(MEMBER_ID, CREATOR_ID, POST_ID);

        assertThat(view.liked()).isFalse();
        assertThat(view.likeCount()).isEqualTo(2);
        assertThat(view.missionAwarded()).isFalse();
        verify(missionCompletionService, never()).completeByPostLike(anyLong(), anyLong(), any());
    }

    private static MissionCompleteOutcome outcome(EarnResultCode code) {
        return new MissionCompleteOutcome(code, 7L, 1, Instant.parse("2026-10-13T00:00:00Z"));
    }
}
