package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberNicknameReservation;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberNicknameReservationRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class MemberNicknameServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberNicknameReservationRepository reservationRepository = mock(MemberNicknameReservationRepository.class);
    private final MemberNicknameContentPolicy contentPolicy = mock(MemberNicknameContentPolicy.class);
    private final MemberNicknameService service = new MemberNicknameService(
            new MemberNicknameTransactionalService(
                    memberRepository,
                    new MemberNicknameRegistry(memberRepository, reservationRepository),
                    contentPolicy,
                    Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
    );

    private Member member;

    @BeforeEach
    void setUp() {
        member = new Member("OAuth 이름", null, null, MemberRole.USER);
        ReflectionTestUtils.setField(member, "memberId", 1L);
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(memberRepository.existsByNicknameNormalized(any())).willReturn(false);
        given(reservationRepository.findByNicknameNormalized(any())).willReturn(Optional.empty());
    }

    @Test
    void 최초_설정은_즉시_허용하고_변경_시계를_시작하지_않는다() {
        MemberNicknameService.MemberNicknameResult result = service.change(1L, "first");

        assertThat(result.nickname()).isEqualTo("first");
        assertThat(result.nicknameChangeableAt()).isNull();
        then(memberRepository).should().saveAndFlush(member);
    }

    @Test
    void 같은_닉네임_요청은_no_op으로_성공한다() {
        member.setInitialNickname(MemberNickname.of("first"));

        MemberNicknameService.MemberNicknameResult result = service.change(1L, "FIRST");

        assertThat(result.nickname()).isEqualTo("first");
        then(memberRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 최초_설정_이후_실제_변경은_이전_닉네임을_예약하고_14일_제한을_시작한다() {
        member.setInitialNickname(MemberNickname.of("first"));

        MemberNicknameService.MemberNicknameResult result = service.change(1L, "second");

        assertThat(result.nickname()).isEqualTo("second");
        assertThat(result.previousNickname()).isEqualTo("first");
        assertThat(result.nicknameChangeableAt()).isEqualTo(NOW.plusDays(14));
        then(reservationRepository).should().save(any(MemberNicknameReservation.class));
    }

    @Test
    void 변경_가능_시각_전의_새_닉네임_변경은_거절한다() {
        member.setInitialNickname(MemberNickname.of("first"));
        member.changeNickname(MemberNickname.of("second"), NOW.minusDays(13));

        assertError(() -> service.change(1L, "third"), MemberErrorCode.NICKNAME_CHANGE_TOO_SOON);
    }

    @Test
    void 예약된_직전_닉네임으로의_복귀는_변경시각을_갱신하지_않는다() {
        member.setInitialNickname(MemberNickname.of("first"));
        member.changeNickname(MemberNickname.of("second"), NOW.minusDays(1));
        MemberNickname first = MemberNickname.of("first");
        given(reservationRepository.findByNicknameNormalized(first.normalized()))
                .willReturn(Optional.of(MemberNicknameReservation.reserve(first, 1L, NOW.minusDays(1))));

        MemberNicknameService.MemberNicknameResult result = service.change(1L, "first");

        assertThat(result.nickname()).isEqualTo("first");
        assertThat(result.previousNickname()).isEqualTo("second");
        assertThat(result.nicknameChangeableAt()).isEqualTo(NOW.plusDays(13));
    }

    @Test
    void 다른_회원이_예약한_닉네임은_거절한다() {
        MemberNickname reserved = MemberNickname.of("taken");
        given(reservationRepository.findByNicknameNormalized(reserved.normalized()))
                .willReturn(Optional.of(MemberNicknameReservation.reserve(reserved, 2L, NOW)));

        assertError(() -> service.change(1L, "taken"), MemberErrorCode.NICKNAME_ALREADY_TAKEN);
    }

    @Test
    void 만료된_예약은_정리하고_새_회원이_쓸_수_있다() {
        MemberNickname expired = MemberNickname.of("expired");
        MemberNicknameReservation reservation = MemberNicknameReservation.reserve(expired, 2L, NOW.minusDays(14));
        given(reservationRepository.findByNicknameNormalized(expired.normalized())).willReturn(Optional.of(reservation));

        service.change(1L, "expired");

        then(reservationRepository).should().delete(reservation);
    }

    @Test
    void 동시_최종_고유성_충돌은_이미_사용중_오류로_변환한다() {
        given(memberRepository.saveAndFlush(member)).willThrow(new DataIntegrityViolationException("duplicate"));

        assertError(() -> service.change(1L, "race"), MemberErrorCode.NICKNAME_ALREADY_TAKEN);
    }

    @Test
    void 형식_위반은_공통_검증_오류다() {
        assertError(() -> service.change(1L, "닉 네임"), CommonErrorCode.VALIDATION_FAILED);
    }

    private static void assertError(Runnable action, Object expected) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }
}
