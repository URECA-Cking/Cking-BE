package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.co.cking.abuse.application.classification.MissionResultClassifier;
import kr.co.cking.abuse.application.context.MissionBusinessKeyFactory;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MissionAbuseObserverTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-06T23:59:59Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-07T00:00:01Z");

    private final AbuseObservationExecutor executor = mock(AbuseObservationExecutor.class);
    private final MissionAbuseObserver observer = new MissionAbuseObserver(
            new MissionResultClassifier(), new MissionBusinessKeyFactory(), executor,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));

    @Test
    void Creator_LIKE는_EARN_기간_시각의_DAILY_Key와_전용_BalanceScope를_사용한다() {
        MissionObservationContext context = creator(MissionType.LIKE);

        observer.observeSuccess(context, EarnResultCode.EARN_ACCEPTED);

        AbuseObservationEvent event = captured();
        assertThat(event.userId()).isEqualTo(17L);
        assertThat(event.requestId()).isEqualTo(context.requestId());
        assertThat(event.resultCode()).isEqualTo("EARN_ACCEPTED");
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.NEW_SUCCESS);
        assertThat(event.businessKey()).isEqualTo("MISSION:CREATOR:DAILY:17:5:103:2026-10-06");
        assertThat(event.periodKey()).isEqualTo("2026-10-06");
        assertThat(event.balanceScope()).isEqualTo(BalanceScope.creator(5L));
        assertThat(event.requestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
    }

    @Test
    void Creator_SHARE_Replay는_날짜가_없는_ONCE_Key로_관찰한다() {
        MissionObservationContext context = creator(MissionType.SHARE);

        observer.observeSuccess(context, EarnResultCode.ALREADY_PROCESSED);

        AbuseObservationEvent event = captured();
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.REPLAY);
        assertThat(event.businessKey()).isEqualTo("MISSION:CREATOR:ONCE:17:5:103");
        assertThat(event.periodKey()).isNull();
    }

    @Test
    void 동일_requestId의_두_시도는_서로_다른_ObservationId를_사용한다() {
        MissionObservationContext context = creator(MissionType.LIKE);

        observer.observeSuccess(context, EarnResultCode.EARN_ACCEPTED);
        observer.observeSuccess(context, EarnResultCode.ALREADY_PROCESSED);

        ArgumentCaptor<AbuseObservationEvent> captor = ArgumentCaptor.forClass(AbuseObservationEvent.class);
        verify(executor, times(2)).observe(captor.capture());
        assertThat(captor.getAllValues()).extracting(AbuseObservationEvent::requestId)
                .containsExactly(context.requestId(), context.requestId());
        assertThat(captor.getAllValues().get(0).observationId())
                .isNotEqualTo(captor.getAllValues().get(1).observationId());
    }

    @Test
    void 공용_ATTENDANCE_중복_오류는_공용_DAILY_Key로_관찰한다() {
        MissionObservationContext context = MissionObservationContext.common(
                17L, 103L, UUID.randomUUID(), REQUESTED_AT, CommonMissionType.ATTENDANCE);

        observer.observeFailure(context, MissionErrorCode.DUPLICATE_MISSION);

        AbuseObservationEvent event = captured();
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.BUSINESS_FAILURE);
        assertThat(event.businessKey()).isEqualTo("MISSION:COMMON:DAILY:17:103:2026-10-06");
        assertThat(event.creatorId()).isNull();
        assertThat(event.balanceScope()).isEqualTo(BalanceScope.common());
    }

    @Test
    void 리소스_미존재와_예상치_못한_오류는_서로_다르게_분류한다() {
        MissionObservationContext context = creator(MissionType.LIKE);

        observer.observeFailure(context, CommonErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(executor);

        observer.observeUnexpectedFailure(context);
        AbuseObservationEvent event = captured();
        assertThat(event.resultCode()).isEqualTo("SYSTEM_ERROR");
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.SYSTEM_FAILURE);
    }

    @Test
    void 관찰_Context_생성_오류도_원_업무로_전파하지_않는다() {
        MissionObservationContext unsupported = creator(MissionType.YOUTUBE_SUBSCRIPTION);

        assertThatCode(() -> observer.observeSuccess(unsupported, EarnResultCode.EARN_ACCEPTED))
                .doesNotThrowAnyException();
        verifyNoInteractions(executor);
    }

    @Test
    void 관찰_실행기가_실패해도_성공과_업무_실패_결과를_변경하지_않는다() {
        MissionObservationContext context = creator(MissionType.LIKE);
        doThrow(new IllegalStateException("Redis failure"))
                .when(executor).observe(org.mockito.ArgumentMatchers.any(AbuseObservationEvent.class));

        assertThatCode(() -> observer.observeSuccess(context, EarnResultCode.EARN_ACCEPTED))
                .doesNotThrowAnyException();
        assertThatCode(() -> observer.observeFailure(context, MissionErrorCode.DUPLICATE_MISSION))
                .doesNotThrowAnyException();
    }

    private MissionObservationContext creator(MissionType type) {
        return MissionObservationContext.creator(17L, 5L, 103L, UUID.randomUUID(), REQUESTED_AT, type);
    }

    private AbuseObservationEvent captured() {
        ArgumentCaptor<AbuseObservationEvent> captor = ArgumentCaptor.forClass(AbuseObservationEvent.class);
        verify(executor).observe(captor.capture());
        return captor.getValue();
    }
}
