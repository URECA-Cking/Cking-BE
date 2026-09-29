package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.common.storage.PutResult;
import kr.co.cking.subscriptionverification.application.image.ProcessedSubscriptionImage;
import kr.co.cking.subscriptionverification.application.image.SubscriptionImageProcessor;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionVerificationSubmissionServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CREATOR_ID = 42L;
    private static final Long MISSION_ID = 103L;
    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final SubscriptionImageProcessor imageProcessor = mock(SubscriptionImageProcessor.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final SubscriptionVerificationAvailability availability =
            mock(SubscriptionVerificationAvailability.class);
    private final SubscriptionVerificationSubmissionValidator validator =
            mock(SubscriptionVerificationSubmissionValidator.class);
    private final SubscriptionVerificationPersistenceService persistenceService =
            mock(SubscriptionVerificationPersistenceService.class);

    private SubscriptionVerificationSubmissionService service;

    @BeforeEach
    void setUp() {
        service = service();
        given(imageProcessor.process(any())).willReturn(processedImage());
        given(validator.validateRequest(any(), any(), any(), any(), any(), any()))
                .willReturn(Optional.empty());
        given(objectStorage.put(any(), any(), any()))
                .willAnswer(invocation -> new PutResult(
                        invocation.getArgument(0),
                        ((byte[]) invocation.getArgument(1)).length,
                        "etag"));
    }

    @Test
    void 기능이_꺼져_있으면_이미지를_처리하지_않는다() {
        doThrow(new BusinessException(SubscriptionVerificationErrorCode.VERIFICATION_UNAVAILABLE))
                .when(availability).requireSubmissionEnabled();

        assertThatThrownBy(() -> service.submit(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SubscriptionVerificationErrorCode.VERIFICATION_UNAVAILABLE);

        then(imageProcessor).should(never()).process(any());
        then(objectStorage).should(never()).put(any(), any(), any());
    }

    @Test
    void 정규화_이미지를_저장하고_PENDING_생성을_요청한다() {
        SubscriptionVerification verification = verification(123L, REQUEST_ID.toString());
        given(persistenceService.create(any()))
                .willReturn(new SubscriptionVerificationSubmissionResult(verification, true));

        SubscriptionVerificationSubmissionResult result = service.submit(command());

        assertThat(result.created()).isTrue();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        then(objectStorage).should().put(
                keyCaptor.capture(),
                aryEq(bytes("normalized")),
                org.mockito.ArgumentMatchers.eq("image/jpeg"));
        assertThat(keyCaptor.getValue())
                .matches("subscription-verifications/2026/09/[0-9a-f-]{36}/image\\.jpg");

        ArgumentCaptor<SubscriptionVerificationPersistenceCommand> commandCaptor =
                ArgumentCaptor.forClass(SubscriptionVerificationPersistenceCommand.class);
        then(persistenceService).should().create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().requestId()).isEqualTo(REQUEST_ID.toString());
        assertThat(commandCaptor.getValue().imageObjectKey()).isEqualTo(keyCaptor.getValue());
        assertThat(commandCaptor.getValue().imageSha256()).isEqualTo("b".repeat(64));
    }

    @Test
    void 사전_멱등_요청은_Object를_저장하지_않고_기존_결과를_반환한다() {
        SubscriptionVerification existing = verification(123L, REQUEST_ID.toString());
        given(validator.validateRequest(any(), any(), any(), any(), any(), any()))
                .willReturn(Optional.of(existing));

        SubscriptionVerificationSubmissionResult result = service.submit(command());

        assertThat(result.created()).isFalse();
        assertThat(result.verification()).isSameAs(existing);
        then(objectStorage).should(never()).put(any(), any(), any());
        then(persistenceService).should(never()).create(any());
    }

    @Test
    void 잠금_후_멱등_요청을_발견하면_이번_Object를_삭제한다() {
        SubscriptionVerification existing = verification(123L, REQUEST_ID.toString());
        given(persistenceService.create(any()))
                .willReturn(new SubscriptionVerificationSubmissionResult(existing, false));

        SubscriptionVerificationSubmissionResult result = service.submit(command());

        assertThat(result.created()).isFalse();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        then(objectStorage).should().delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("subscription-verifications/2026/09/");
    }

    @Test
    void DB_저장이_실패하면_업로드한_Object를_삭제한다() {
        given(persistenceService.create(any())).willThrow(new IllegalStateException("db failure"));

        assertThatThrownBy(() -> service.submit(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db failure");

        then(objectStorage).should().delete(any());
    }

    @Test
    void Object_보상_삭제가_실패해도_원래_DB_예외를_보존한다() {
        IllegalStateException databaseFailure = new IllegalStateException("db failure");
        given(persistenceService.create(any())).willThrow(databaseFailure);
        doThrow(new IllegalStateException("cleanup failure")).when(objectStorage).delete(any());

        assertThatThrownBy(() -> service.submit(command())).isSameAs(databaseFailure);
    }

    @Test
    void DB_UNIQUE_경쟁은_Object를_삭제하고_기존_멱등_결과로_수렴한다() {
        SubscriptionVerification existing = verification(123L, REQUEST_ID.toString());
        given(persistenceService.create(any()))
                .willThrow(new DataIntegrityViolationException("request unique"));
        given(validator.validateRequest(any(), any(), any(), any(), any(), any()))
                .willReturn(Optional.empty(), Optional.of(existing));

        SubscriptionVerificationSubmissionResult result = service.submit(command());

        assertThat(result.created()).isFalse();
        assertThat(result.verification()).isSameAs(existing);
        then(objectStorage).should().delete(any());
    }

    private SubscriptionVerificationSubmissionService service() {
        return new SubscriptionVerificationSubmissionService(
                imageProcessor,
                objectStorage,
                availability,
                validator,
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SubscriptionVerificationSubmissionCommand command() {
        return new SubscriptionVerificationSubmissionCommand(
                MEMBER_ID, CREATOR_ID, MISSION_ID, REQUEST_ID, bytes("source"));
    }

    private ProcessedSubscriptionImage processedImage() {
        return new ProcessedSubscriptionImage(
                bytes("normalized"), "a".repeat(64), "b".repeat(64), "JPEG_V1", 480, 480);
    }

    private SubscriptionVerification verification(Long id, String requestId) {
        SubscriptionVerification verification = SubscriptionVerification.pending(
                MEMBER_ID,
                CREATOR_ID,
                MISSION_ID,
                requestId,
                SubscriptionVerificationFingerprint.calculate(
                        MEMBER_ID, CREATOR_ID, MISSION_ID, "b".repeat(64)),
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/id/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                UUID.randomUUID().toString(),
                NOW);
        ReflectionTestUtils.setField(verification, "verificationId", id);
        return verification;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
