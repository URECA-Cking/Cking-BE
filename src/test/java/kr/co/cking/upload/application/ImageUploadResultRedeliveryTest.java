package kr.co.cking.upload.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.creator.application.CreatorSpaceProfileImageUploadService;
import kr.co.cking.event.application.EventBannerImageUploadService;
import kr.co.cking.member.application.MemberProfileImageUploadService;
import kr.co.cking.member.application.image.MemberProfileImageProcessor;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberProfileImage;
import kr.co.cking.member.domain.MemberProfileImageStatus;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.PostImageUploadService;
import kr.co.cking.upload.domain.ImageUpload;
import kr.co.cking.upload.domain.ImageUploadPurpose;
import kr.co.cking.upload.domain.ImageUploadStatus;
import kr.co.cking.upload.repository.ImageUploadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * 결과 반영은 트랜잭션 없이 용도별 기록 → 업로드 순서로 따로 커밋한다. 중간에 끊겨도 메시지를 지우지 않으므로
 * 재전달이 같은 결과로 수렴하는지 실제 MySQL에서 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImageUploadResultRedeliveryTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    private ImageUploadRepository uploadRepository;
    @Autowired
    private MemberProfileImageRepository profileImageRepository;
    @Autowired
    private MemberRepository memberRepository;

    private final SqsClient sqs = mock(SqsClient.class);
    private ImageUploadRepository uploads;
    private MemberProfileImageRepository profileImages;
    private ImageUploadResultListener listener;

    private Long memberId;
    private Long profileImageId;
    private String key;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.save(new Member("업로드 재전달 테스트", null, null, MemberRole.USER)).getMemberId();
        key = "member-profile-images/%d/%s.jpg".formatted(memberId, UUID.randomUUID());
        profileImageId = profileImageRepository.save(MemberProfileImage.uploading(key, memberId, NOW)).getProfileImageId();
        uploadRepository.save(ImageUpload.pending(memberId, ImageUploadPurpose.MEMBER_PROFILE, key, NOW));

        uploads = mock(ImageUploadRepository.class, delegatesTo(uploadRepository));
        profileImages = mock(MemberProfileImageRepository.class, delegatesTo(profileImageRepository));
        listener = new ImageUploadResultListener(sqs, service(uploads, CLOCK), "queue");
    }

    @AfterEach
    void tearDown() {
        uploadRepository.findByObjectKey(key).ifPresent(uploadRepository::delete);
        profileImageRepository.deleteById(profileImageId);
        memberRepository.deleteById(memberId);
    }

    @Test
    void 용도별_기록만_커밋되고_업로드_반영_전에_끊기면_메시지를_남기고_재전달에서_READY로_수렴한다() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .doAnswer(delegatesTo(uploadRepository))
                .when(uploads).markReady(any(), any());

        listener.handle(ready(key));

        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADED);
        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.PENDING);
        assertDeleted(0);

        listener.handle(ready(key));

        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADED);
        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.READY);
        then(profileImages).should(times(2)).markUploaded(key, NOW);
        assertDeleted(1);
    }

    @Test
    void 용도별_기록_반영이_실패하면_아무것도_바뀌지_않고_재전달에서_READY로_수렴한다() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .doAnswer(delegatesTo(profileImageRepository))
                .when(profileImages).markUploaded(any(), any());

        listener.handle(ready(key));

        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADING);
        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.PENDING);
        then(uploads).should(never()).markReady(any(), any());
        assertDeleted(0);

        listener.handle(ready(key));

        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADED);
        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.READY);
        assertDeleted(1);
    }

    @Test
    void 실패_결과_반영이_끊겨도_재전달에서_FAILED로_수렴하고_용도별_기록은_UPLOADING으로_남는다() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .doAnswer(delegatesTo(uploadRepository))
                .when(uploads).markFailed(any(), any(), any());

        listener.handle(failed(key, "dimensions"));

        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.PENDING);
        assertDeleted(0);

        listener.handle(failed(key, "dimensions"));

        assertThat(upload()).extracting(ImageUpload::getStatus, ImageUpload::getFailureReason)
                .containsExactly(ImageUploadStatus.FAILED, "dimensions");
        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADING);
        then(profileImages).should(never()).markUploaded(any(), any());
        assertDeleted(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 반영_뒤_메시지_삭제가_실패해_다시_와도_용도별_기록은_한_번만_바꾸고_메시지를_지운다() {
        doThrow(SdkClientException.create("sqs down")).doReturn(null).when(sqs).deleteMessage(any(Consumer.class));

        listener.handle(ready(key));
        listener.handle(ready(key));

        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.READY);
        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADED);
        then(profileImages).should(times(1)).markUploaded(any(), any());
        then(uploads).should(times(1)).markReady(any(), any());
        assertDeleted(2);
    }

    @Test
    void 두_서버가_같은_결과를_동시에_처리해도_먼저_끝난_반영을_덮지_않는다() {
        ImageUpload readBeforeFirstServerFinished = upload();
        ImageUploadRepository staleUploads = mock(ImageUploadRepository.class, delegatesTo(uploadRepository));
        doReturn(Optional.of(readBeforeFirstServerFinished)).when(staleUploads).findByObjectKey(key);
        ImageUploadService secondServer = service(staleUploads, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        listener.handle(ready(key));
        secondServer.complete(key, ImageUploadStatus.READY, null);

        assertThat(upload()).extracting(ImageUpload::getStatus, ImageUpload::getCompletedAt)
                .containsExactly(ImageUploadStatus.READY, NOW);
        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADED);
    }

    @Test
    void 이미_FAILED인_업로드에_늦은_READY가_오면_바꾸지_않고_메시지만_지운다() {
        uploadRepository.markFailed(key, "expired", NOW);

        listener.handle(ready(key));

        assertThat(upload()).extracting(ImageUpload::getStatus, ImageUpload::getFailureReason)
                .containsExactly(ImageUploadStatus.FAILED, "expired");
        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADING);
        assertDeleted(1);
    }

    /** 재전달 기한(가시성 60초 × 5번)보다 만료(1시간)가 길어 운영에서는 결과 DLQ로 간 뒤에만 생긴다. */
    @Test
    void 중간_상태에서_업로드가_만료되면_용도별_기록만_UPLOADED로_남는다() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .doAnswer(delegatesTo(uploadRepository))
                .when(uploads).markReady(any(), any());
        listener.handle(ready(key));
        uploadRepository.markFailed(key, "expired", NOW);

        listener.handle(ready(key));

        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.FAILED);
        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADED);
        assertDeleted(1);
    }

    @Test
    void 알_수_없는_키의_결과는_아무것도_바꾸지_않고_메시지를_지운다() {
        listener.handle(ready("member-profile-images/%d/%s.jpg".formatted(memberId, UUID.randomUUID())));

        assertThat(upload().getStatus()).isEqualTo(ImageUploadStatus.PENDING);
        assertThat(targetStatus()).isEqualTo(MemberProfileImageStatus.UPLOADING);
        then(profileImages).should(never()).markUploaded(any(), any());
        assertDeleted(1);
    }

    private ImageUploadService service(ImageUploadRepository repository, Clock clock) {
        MemberProfileImageUploadService memberProfile = new MemberProfileImageUploadService(
                mock(MemberRepository.class), mock(CreatorQueryService.class), mock(MemberProfileImageProcessor.class),
                profileImages, mock(ObjectStorage.class), CLOCK);
        ImageUploadTargets targets = new ImageUploadTargets(memberProfile,
                mock(CreatorSpaceProfileImageUploadService.class), mock(EventBannerImageUploadService.class),
                mock(PostImageUploadService.class));
        return new ImageUploadService(repository, targets, mock(ObjectStorage.class), clock);
    }

    private ImageUpload upload() {
        return uploadRepository.findByObjectKey(key).orElseThrow();
    }

    private MemberProfileImageStatus targetStatus() {
        return profileImageRepository.findById(profileImageId).orElseThrow().getStatus();
    }

    @SuppressWarnings("unchecked")
    private void assertDeleted(int count) {
        then(sqs).should(times(count)).deleteMessage(any(Consumer.class));
    }

    private static Message ready(String key) {
        return Message.builder().messageId("m").receiptHandle("r")
                .body("{\"key\":\"%s\",\"status\":\"READY\",\"width\":800,\"height\":600}".formatted(key)).build();
    }

    private static Message failed(String key, String reason) {
        return Message.builder().messageId("m").receiptHandle("r")
                .body("{\"key\":\"%s\",\"status\":\"FAILED\",\"reason\":\"%s\"}".formatted(key, reason)).build();
    }
}
