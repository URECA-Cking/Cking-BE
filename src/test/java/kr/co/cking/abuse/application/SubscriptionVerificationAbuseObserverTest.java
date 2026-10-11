package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.co.cking.abuse.application.context.MissionBusinessKey;
import kr.co.cking.abuse.application.context.MissionBusinessKeyFactory;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionVerificationAbuseObserverTest {

    @Test
    void 다른_회원_이미지_재사용만_별도_구독_인증_관측으로_전달한다() {
        MissionBusinessKeyFactory businessKeyFactory = mock(MissionBusinessKeyFactory.class);
        AbuseObservationExecutor executor = mock(AbuseObservationExecutor.class);
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        given(businessKeyFactory.forCreator(any(), any(), any(), any(), any()))
                .willReturn(new MissionBusinessKey("MISSION:CREATOR:ONCE:7:42:103", null));
        SubscriptionVerificationAbuseObserver observer = new SubscriptionVerificationAbuseObserver(
                businessKeyFactory, executor, Clock.fixed(now, ZoneOffset.UTC));

        observer.observeImageReuse(verification(), SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER);

        ArgumentCaptor<AbuseObservationEvent> captor = ArgumentCaptor.forClass(AbuseObservationEvent.class);
        then(executor).should().observe(captor.capture());
        assertThat(captor.getValue())
                .extracting(AbuseObservationEvent::resultCode, AbuseObservationEvent::businessKey,
                        AbuseObservationEvent::periodKey)
                .containsExactly("IMAGE_REUSE", "MISSION:CREATOR:ONCE:7:42:103", null);
    }

    @Test
    void 동일_회원_재사용은_구독_인증_Abuse_관측을_만들지_않는다() {
        MissionBusinessKeyFactory businessKeyFactory = mock(MissionBusinessKeyFactory.class);
        AbuseObservationExecutor executor = mock(AbuseObservationExecutor.class);
        SubscriptionVerificationAbuseObserver observer = new SubscriptionVerificationAbuseObserver(
                businessKeyFactory, executor, Clock.systemUTC());

        observer.observeImageReuse(verification(), SubscriptionVerificationImageReuseType.SAME_MEMBER_SAME_CREATOR);

        then(executor).shouldHaveNoInteractions();
    }

    private SubscriptionVerification verification() {
        SubscriptionVerification verification = SubscriptionVerification.pending(7L, 42L, 103L,
                UUID.randomUUID().toString(), "f".repeat(64), "채널", "@channel", "object", "a".repeat(64),
                "JPEG_V1", UUID.randomUUID().toString(), Instant.EPOCH);
        ReflectionTestUtils.setField(verification, "verificationId", 1L);
        return verification;
    }
}
