package kr.co.cking.follow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import kr.co.cking.follow.repository.CreatorPostNewsRepository;
import org.junit.jupiter.api.Test;

class CreatorPostNewsServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CREATOR_ID = 3L;
    private static final Instant READ_AT = Instant.parse("2026-10-20T05:30:00Z");

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorFollowRepository followRepository = mock(CreatorFollowRepository.class);
    private final CreatorPostNewsRepository newsRepository = mock(CreatorPostNewsRepository.class);
    private final CreatorPostNewsService service =
            new CreatorPostNewsService(creatorRepository, followRepository, newsRepository);

    @Test
    void 정상_읽음은_갱신과_시각_조회만_수행한다() {
        given(followRepository.markPostsRead(MEMBER_ID, CREATOR_ID)).willReturn(1);
        given(followRepository.findLastReadAt(MEMBER_ID, CREATOR_ID)).willReturn(READ_AT);

        assertThat(service.markRead(MEMBER_ID, CREATOR_ID)).isEqualTo(READ_AT);
        verify(creatorRepository, never()).existsById(CREATOR_ID);
    }

    @Test
    void 변경된_행이_없어도_기록이_있으면_반복_읽음으로_성공한다() {
        given(followRepository.markPostsRead(MEMBER_ID, CREATOR_ID)).willReturn(0);
        given(followRepository.findLastReadAt(MEMBER_ID, CREATOR_ID)).willReturn(READ_AT);

        assertThat(service.markRead(MEMBER_ID, CREATOR_ID)).isEqualTo(READ_AT);
        verify(creatorRepository, never()).existsById(CREATOR_ID);
    }

    @Test
    void 팔로우가_없으면_크리에이터_존재_여부로_403과_404를_구분한다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        assertThatThrownBy(() -> service.markRead(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(FollowErrorCode.FOLLOW_REQUIRED));

        given(creatorRepository.existsById(CREATOR_ID)).willReturn(false);
        assertThatThrownBy(() -> service.markRead(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
