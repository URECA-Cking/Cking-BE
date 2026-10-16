package kr.co.cking.member.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.domain.MemberErrorCode;
import org.junit.jupiter.api.Test;

class MemberProfileUpdateServiceTest {

    private static final Long MEMBER_ID = 10L;

    private final MemberProfileUpdateTransactionalService transactionalService =
            mock(MemberProfileUpdateTransactionalService.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final MemberProfileUpdateService service =
            new MemberProfileUpdateService(transactionalService, creatorQueryService);

    @Test
    void Creator는_개인_프로필을_수정할_수_없다() {
        given(creatorQueryService.isCreatorMember(MEMBER_ID)).willReturn(true);

        assertThatThrownBy(() -> service.update(MEMBER_ID,
                new MemberProfileUpdateService.UpdateFields("팬닉네임", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.CREATOR_PROFILE_MANAGED_IN_SPACE);

        verify(transactionalService, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 팬은_기존_프로필_수정_경로를_사용한다() {
        MemberProfileUpdateService.UpdateFields fields =
                new MemberProfileUpdateService.UpdateFields("팬닉네임", null, false);

        service.update(MEMBER_ID, fields);

        verify(transactionalService).update(MEMBER_ID, fields);
    }
}
