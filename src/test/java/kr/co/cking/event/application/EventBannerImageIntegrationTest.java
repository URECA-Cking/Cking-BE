package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.application.dto.UpdateEventCommand;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventBannerImage;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventBannerImageRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Event 배너 연결이 생성·수정·삭제와 같은 트랜잭션에서 업로드 기록 상태를 바꾸는지 DB로 검증한다(이슈 #748). */
@SpringBootTest
class EventBannerImageIntegrationTest {

    private static final String REQUEST_ID_PREFIX = "748e8400-e29b-41d4-a716-4466554400";

    @Autowired private CreatorEventService creatorEventService;
    @Autowired private EventReviewService eventReviewService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventBannerImageRepository bannerImageRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorIds = new ArrayList<>();

    @BeforeEach
    void cleanTestEvents() {
        jdbcTemplate.update("DELETE FROM event_approval_request WHERE event_id IN (SELECT event_id FROM event WHERE request_id LIKE ?)", REQUEST_ID_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM event WHERE request_id LIKE ?", REQUEST_ID_PREFIX + "%");
    }

    @AfterEach
    void cleanUp() {
        cleanTestEvents();
        creatorIds.forEach(creatorId -> jdbcTemplate.update("DELETE FROM event_banner_image WHERE creator_id = ?", creatorId));
        creatorRepository.deleteAllById(creatorIds);
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    void 생성_시_본인_업로드_키를_연결하고_같은_requestId_재시도는_다시_연결하지_않고_기존_Event를_돌려준다() {
        Creator creator = creator("배너 생성");
        String key = uploaded(creator, "create");
        CreateEventCommand command = create(creator, "01", key);

        Event first = creatorEventService.create(command);
        Event retried = creatorEventService.create(command);

        assertThat(retried.getEventId()).isEqualTo(first.getEventId());
        Event persisted = eventRepository.findById(first.getEventId()).orElseThrow();
        assertThat(persisted.getBannerImageKey()).isEqualTo(key);
        assertThat(persisted.getBannerImageUrl()).endsWith("/" + key);
        assertThat(status(key)).isEqualTo("LINKED");
    }

    @Test
    void 같은_requestId에_다른_배너를_보내면_멱등성_충돌이다() {
        Creator creator = creator("배너 멱등");
        creatorEventService.create(create(creator, "02", uploaded(creator, "first")));
        String other = uploaded(creator, "other");

        assertThatThrownBy(() -> creatorEventService.create(create(creator, "02", other)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(status(other)).isEqualTo("UPLOADED");
    }

    @Test
    void 다른_Creator의_키로_생성하면_INVALID_EVENT_BANNER_IMAGE이고_Event도_남지_않는다() {
        Creator owner = creator("배너 주인");
        Creator other = creator("다른 크리에이터");
        String key = uploaded(owner, "owner");

        assertThatThrownBy(() -> creatorEventService.create(create(other, "03", key)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_EVENT_BANNER_IMAGE);
        assertThat(eventRepository.findByRequestId(REQUEST_ID_PREFIX + "03")).isEmpty();
        assertThat(status(key)).isEqualTo("UPLOADED");
    }

    @Test
    void 수정은_키를_생략하면_유지하고_새_키면_교체하며_null이면_떼고_풀린_배너는_삭제_대기다() {
        Creator creator = creator("배너 수정");
        String first = uploaded(creator, "first");
        Event event = creatorEventService.create(create(creator, "04", first));

        creatorEventService.update(update(creator, event, null, false));
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getBannerImageKey()).isEqualTo(first);

        String second = uploaded(creator, "second");
        creatorEventService.update(update(creator, event, second, true));
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getBannerImageKey()).isEqualTo(second);
        assertThat(status(first)).isEqualTo("DELETE_PENDING");
        assertThat(status(second)).isEqualTo("LINKED");

        creatorEventService.update(update(creator, event, null, true));
        Event removed = eventRepository.findById(event.getEventId()).orElseThrow();
        assertThat(removed.getBannerImageKey()).isNull();
        assertThat(removed.getBannerImageUrl()).isNull();
        assertThat(status(second)).isEqualTo("DELETE_PENDING");
    }

    @Test
    void 승인_대기_Event는_배너를_바꿀_수_없고_반려되면_배너를_바꾸며_초안으로_돌아간다() {
        Creator creator = creator("배너 반려");
        Member admin = member("관리자", MemberRole.ADMIN);
        Event event = creatorEventService.create(create(creator, "05", null));
        creatorEventService.requestApproval(creator.getMemberId(), event.getEventId());

        String pendingKey = uploaded(creator, "pending");
        assertThatThrownBy(() -> creatorEventService.update(update(creator, event, pendingKey, true)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
        assertThat(status(pendingKey)).isEqualTo("UPLOADED");

        eventReviewService.reject(admin.getMemberId(), event.getEventId(), "배너를 바꿔 주세요.");
        creatorEventService.update(update(creator, event, pendingKey, true));

        Event updated = eventRepository.findById(event.getEventId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(updated.getBannerImageKey()).isEqualTo(pendingKey);
        assertThat(status(pendingKey)).isEqualTo("LINKED");
    }

    @Test
    void 삭제한_Event의_배너는_삭제_대기로_돌린다() {
        Creator creator = creator("배너 삭제");
        String key = uploaded(creator, "delete");
        Event event = creatorEventService.create(create(creator, "06", key));

        creatorEventService.delete(creator.getMemberId(), event.getEventId());

        assertThat(status(key)).isEqualTo("DELETE_PENDING");
    }

    private CreateEventCommand create(Creator creator, String requestSuffix, String bannerImageKey) {
        return new CreateEventCommand(creator.getMemberId(), REQUEST_ID_PREFIX + requestSuffix, "배너 이벤트", null,
                Instant.parse("2030-01-02T03:04:05Z"), Instant.parse("2030-01-03T03:04:05Z"), 1, DrawMethod.WEIGHTED,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, List.of(), bannerImageKey);
    }

    private UpdateEventCommand update(Creator creator, Event event, String bannerImageKey, boolean provided) {
        return new UpdateEventCommand(creator.getMemberId(), event.getEventId(), "배너 이벤트", null,
                Instant.parse("2030-01-02T03:04:05Z"), Instant.parse("2030-01-03T03:04:05Z"), 1, DrawMethod.WEIGHTED,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, List.of(), bannerImageKey, provided);
    }

    /** 저장소 업로드를 거치지 않고, 업로드가 끝난 배너 기록만 만든다. */
    private String uploaded(Creator creator, String name) {
        String key = "creator-space-profile-images/%d/event-banner/%s-%s.jpg"
                .formatted(creator.getCreatorId(), name, java.util.UUID.randomUUID());
        Instant now = Instant.now().minus(Duration.ofMinutes(1));
        bannerImageRepository.save(EventBannerImage.uploading(key, creator.getCreatorId(), now));
        bannerImageRepository.markUploaded(key, now);
        return key;
    }

    private String status(String key) {
        return jdbcTemplate.queryForObject("SELECT status FROM event_banner_image WHERE object_key = ?", String.class, key);
    }

    private Creator creator(String name) {
        Member member = member(name, MemberRole.USER);
        Creator creator = creatorRepository.save(new Creator(member.getMemberId(), member.getName()));
        creatorIds.add(creator.getCreatorId());
        return creator;
    }

    private Member member(String name, MemberRole role) {
        Member member = memberRepository.save(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }
}
