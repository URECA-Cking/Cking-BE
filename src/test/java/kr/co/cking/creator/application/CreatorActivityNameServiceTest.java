package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.domain.CreatorActivityNameReservation;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.application.MemberNicknameContentPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorActivityNameServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorActivityNameRegistry registry = mock(CreatorActivityNameRegistry.class);
    private final MemberNicknameContentPolicy contentPolicy = mock(MemberNicknameContentPolicy.class);
    private final CreatorApplicationLockManager lockManager = mock(CreatorApplicationLockManager.class);
    private final CreatorActivityNameService service = new CreatorActivityNameService(
            creatorRepository, registry, contentPolicy, lockManager,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private Creator creator;

    @BeforeEach
    void setUp() {
        creator = new Creator(10L, "OAuth 이름");
        ReflectionTestUtils.setField(creator, "creatorId", 1L);
        ReflectionTestUtils.setField(creator, "activityNameConfigured", false);
        given(creatorRepository.findByMemberIdForUpdate(10L)).willReturn(Optional.of(creator));
        given(registry.isTaken(any(), any(), any())).willReturn(false);
        given(lockManager.executeActivityNameAllocation(any())).willAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    void 최초_설정은_즉시_허용하고_변경_시계를_시작하지_않는다() {
        CreatorActivityNameService.ActivityNameProfile result = service.updateMine(10L, "CreatorOne");

        assertThat(result.activityName()).isEqualTo("CreatorOne");
        assertThat(result.activityNameConfigured()).isTrue();
        assertThat(result.activityNameChangeableAt()).isNull();
        then(creatorRepository).should().saveAndFlush(creator);
    }

    @Test
    void 이관된_Creator는_임시_활동명을_그대로_확정할_수_있다() {
        CreatorActivityName temporary = CreatorActivityName.temporaryFor(10L);
        ReflectionTestUtils.setField(creator, "activityName", temporary.value());
        ReflectionTestUtils.setField(creator, "activityNameNormalized", temporary.normalized());

        CreatorActivityNameService.ActivityNameProfile result = service.updateMine(10L, temporary.value());

        assertThat(result.activityName()).isEqualTo(temporary.value());
        assertThat(result.activityNameConfigured()).isTrue();
        assertThat(result.activityNameChangeableAt()).isNull();
        then(creatorRepository).should().saveAndFlush(creator);
    }

    @Test
    void 최초_설정_이후_실제_변경은_이전_활동명을_예약하고_14일_제한을_시작한다() {
        creator.setInitialActivityName(CreatorActivityName.of("first"));
        CreatorActivityName first = CreatorActivityName.of("first");
        given(registry.findOwnedActive(first, 1L, NOW))
                .willReturn(Optional.of(CreatorActivityNameReservation.reserve(first, 1L, NOW)));

        CreatorActivityNameService.ActivityNameProfile result = service.updateMine(10L, "second");

        assertThat(result.activityName()).isEqualTo("second");
        assertThat(result.restorableActivityName()).isEqualTo("first");
        assertThat(result.activityNameChangeableAt()).isEqualTo(NOW.plusDays(14).toInstant(ZoneOffset.UTC));
        then(registry).should().reserveReleased(CreatorActivityName.of("first"), 1L, NOW);
    }

    @Test
    void 변경_가능_시각_전의_새_활동명_변경은_거절한다() {
        creator.setInitialActivityName(CreatorActivityName.of("first"));
        creator.changeActivityName(CreatorActivityName.of("second"), NOW.minusDays(13));

        assertError(() -> service.updateMine(10L, "third"), CreatorErrorCode.ACTIVITY_NAME_CHANGE_TOO_SOON);
    }

    @Test
    void 동시_최종_고유성_충돌은_이미_사용중_오류로_변환한다() {
        given(creatorRepository.saveAndFlush(creator)).willThrow(new DataIntegrityViolationException("duplicate"));

        assertError(() -> service.updateMine(10L, "race"), CreatorErrorCode.ACTIVITY_NAME_ALREADY_TAKEN);
    }

    @Test
    void 형식_위반은_공통_검증_오류다() {
        assertError(() -> service.updateMine(10L, "닉 네임"), CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 승인_최초_활동명은_충돌하면_접미사를_붙인다() {
        given(registry.isTaken(CreatorActivityName.of("creator"), null, NOW)).willReturn(true);

        CreatorActivityName result = service.initialAvailableName("creator");

        assertThat(result.value()).isEqualTo("creator-2");
    }

    private static void assertError(Runnable action, Object expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }
}
