package kr.co.cking.member;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.member.presentation.UserSelectionResponse;
import kr.co.cking.member.presentation.UserSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberQueryServiceTest {

    private final MemberRepository repository = mock(MemberRepository.class);
    private final MemberQueryService service = new MemberQueryService(repository);

    @Test
    void 사용자_목록을_userId와_이름으로_변환한다() {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(1L);
        when(member.getName()).thenReturn("홍길동");
        when(repository.findAll()).thenReturn(List.of(member));

        List<UserSummary> result = service.findUsers();

        assertThat(result).containsExactly(new UserSummary(1L, "홍길동"));
    }

    @Test
    void 존재하는_사용자를_선택하면_선택_응답을_반환한다() {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(1L);
        when(member.getName()).thenReturn("홍길동");
        when(repository.findById(1L)).thenReturn(Optional.of(member));

        UserSelectionResponse result = service.selectUser(1L);

        assertThat(result).isEqualTo(new UserSelectionResponse(1L, "홍길동", true));
    }

    @Test
    void 존재하지_않는_사용자_선택은_RESOURCE_NOT_FOUND다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectUser(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void ADMIN_사용자는_관리자_검증을_통과한다() {
        Member member = mock(Member.class);
        when(member.getRole()).thenReturn(MemberRole.ADMIN);
        when(repository.findById(1L)).thenReturn(Optional.of(member));

        service.validateAdmin(1L);
    }

    @Test
    void 존재하지_않는_관리자는_RESOURCE_NOT_FOUND다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAdmin(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void ADMIN이_아닌_사용자는_FORBIDDEN이다() {
        Member member = mock(Member.class);
        when(member.getRole()).thenReturn(MemberRole.USER);
        when(repository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.validateAdmin(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 사용자_ID_목록을_한번에_사용자_정보로_조회한다() {
        Member first = mock(Member.class);
        when(first.getMemberId()).thenReturn(1L);
        when(first.getName()).thenReturn("홍길동");
        Member second = mock(Member.class);
        when(second.getMemberId()).thenReturn(2L);
        when(second.getName()).thenReturn("김철수");
        when(repository.findByMemberIdIn(List.of(1L, 2L))).thenReturn(List.of(second, first));

        Map<Long, MemberInfo> result = service.findMemberInfosByIds(List.of(1L, 2L));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, new MemberInfo(1L, "홍길동"),
                2L, new MemberInfo(2L, "김철수")
        ));
    }
}
