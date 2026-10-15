package kr.co.cking.post.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PostAuthorLookupTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final PostAuthorLookup lookup = new PostAuthorLookup(memberRepository, creatorRepository);

    @Test
    void 활동명이_설정된_Creator만_게시글_작성자로_반환한다() {
        Creator creator = creator(10L, true);
        given(memberRepository.findById(10L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorRepository.findByMemberId(10L)).willReturn(Optional.of(creator));

        assertThat(lookup.requireCreator(10L)).isSameAs(creator);
    }

    @Test
    void 활동명_미설정_Creator는_게시글_작성자가_될_수_없다() {
        given(memberRepository.findById(10L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorRepository.findByMemberId(10L)).willReturn(Optional.of(creator(10L, false)));

        assertThatThrownBy(() -> lookup.requireCreator(10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED);
    }

    private Creator creator(Long memberId, boolean activityNameConfigured) {
        Creator creator = new Creator(memberId, "creator-1");
        ReflectionTestUtils.setField(creator, "activityNameConfigured", activityNameConfigured);
        return creator;
    }
}
