package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.ResolvingDrawingEngine;
import kr.co.cking.drawing.domain.engine.UniformV1DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventEntry;
import kr.co.cking.event.domain.PrizeConfig;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.redraw.application.RedrawRequestCreateCommand;
import kr.co.cking.redraw.application.RedrawRequestCreateService;
import kr.co.cking.redraw.application.RedrawRequestExecutionResult;
import kr.co.cking.redraw.application.RedrawRequestExecutionService;
import kr.co.cking.redraw.application.RedrawRequestReviewService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** REDRAW 기술 실패 뒤 동일 Drawing·Seed·상품 승계 입력으로 Retry하는 전체 영속 계약을 검증한다. */
@SpringBootTest
@Import(RedrawDrawingRetryIntegrationTest.FailFirstRedrawEngineConfiguration.class)
class RedrawDrawingRetryIntegrationTest {

    @Autowired MemberRepository memberRepository;
    @Autowired CreatorRepository creatorRepository;
    @Autowired EventRepository eventRepository;
    @Autowired EventEntryRepository entryRepository;
    @Autowired OfficialSnapshotService snapshotService;
    @Autowired InitialDrawingExecutionService initialDrawingExecutionService;
    @Autowired RedrawRequestCreateService redrawRequestCreateService;
    @Autowired RedrawRequestReviewService redrawRequestReviewService;
    @Autowired RedrawRequestExecutionService redrawRequestExecutionService;
    @Autowired DrawingRetryService drawingRetryService;
    @Autowired DrawingRepository drawingRepository;
    @Autowired DrawSeedRepository seedRepository;
    @Autowired WinnerRepository winnerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private Long adminId;
    private Long creatorId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        Member admin = saveMember("REDRAW Retry 관리자", MemberRole.ADMIN);
        adminId = admin.getMemberId();
        Member owner = saveMember("REDRAW Retry 크리에이터", MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), owner.getName()));
        creatorId = creator.getCreatorId();
        Member first = saveMember("REDRAW 후보 1", MemberRole.USER);
        Member second = saveMember("REDRAW 후보 2", MemberRole.USER);
        Member third = saveMember("REDRAW 후보 3", MemberRole.USER);

        Event event = eventRepository.saveAndFlush(new Event(
                creatorId,
                "REDRAW 실패 Retry 통합 테스트",
                "동일 Drawing과 Seed 재사용",
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-15T00:00:00Z"),
                1,
                DrawMethod.WEIGHTED,
                owner.getMemberId(),
                UUID.randomUUID().toString(),
                List.of(new PrizeConfig("FIRST", "10만원권", 1, 1L, 1))));
        eventId = event.getEventId();
        jdbcTemplate.update("UPDATE event SET status = 'CLOSED' WHERE event_id = ?", eventId);
        entryRepository.saveAllAndFlush(List.of(
                entry(first.getMemberId(), 3L),
                entry(second.getMemberId(), 2L),
                entry(third.getMemberId(), 1L)));
    }

    @AfterEach
    void tearDown() {
        if (eventId == null) {
            return;
        }
        List<Long> seedIds = jdbcTemplate.queryForList(
                "SELECT seed_id FROM drawing WHERE event_id = ?", Long.class, eventId);
        jdbcTemplate.update("""
                DELETE history FROM redraw_execution_history history
                JOIN redraw_request request ON request.id = history.redraw_request_id
                WHERE request.event_id = ?
                """, eventId);
        jdbcTemplate.update("""
                DELETE vacancy FROM redraw_request_vacancy vacancy
                JOIN redraw_request request ON request.id = vacancy.redraw_request_id
                WHERE request.event_id = ?
                """, eventId);
        jdbcTemplate.update("""
                DELETE management FROM winner_management management
                JOIN winner ON winner.id = management.winner_id
                WHERE winner.event_id = ?
                """, eventId);
        jdbcTemplate.update("DELETE FROM winner WHERE event_id = ?", eventId);
        jdbcTemplate.update("""
                DELETE exclusion FROM redraw_exclusion exclusion
                JOIN drawing ON drawing.id = exclusion.drawing_id
                WHERE drawing.event_id = ?
                """, eventId);
        jdbcTemplate.update("""
                DELETE attempt FROM draw_attempt_history attempt
                JOIN drawing ON drawing.id = attempt.drawing_id
                WHERE drawing.event_id = ?
                """, eventId);
        jdbcTemplate.update("DELETE FROM drawing WHERE event_id = ? AND draw_type = 'REDRAW'", eventId);
        jdbcTemplate.update("DELETE FROM redraw_request WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM drawing WHERE event_id = ? AND draw_type = 'INITIAL'", eventId);
        seedIds.forEach(seedRepository::deleteById);
        jdbcTemplate.update("""
                DELETE candidate FROM draw_snapshot_candidate candidate
                JOIN draw_snapshot snapshot ON snapshot.id = candidate.snapshot_id
                WHERE snapshot.event_id = ?
                """, eventId);
        jdbcTemplate.update("""
                DELETE prize FROM draw_snapshot_prize prize
                JOIN draw_snapshot snapshot ON snapshot.id = prize.snapshot_id
                WHERE snapshot.event_id = ?
                """, eventId);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM event_prize WHERE event_id = ?", eventId);
        eventRepository.deleteById(eventId);
        creatorRepository.deleteById(creatorId);
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    void REDRAW_실패_후_같은_Drawing과_Seed로_상품을_승계해_Retry한다() {
        snapshotService.createIfAbsent(eventId);
        InitialDrawingResult initialResult = initialDrawingExecutionService.execute(adminId, eventId);
        Long initialDrawingId = initialResult.drawingId();
        Winner originalWinner = winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(initialDrawingId)
                .getFirst();
        jdbcTemplate.update("UPDATE drawing SET visibility = 'PUBLIC' WHERE id = ?", initialDrawingId);
        jdbcTemplate.update("UPDATE event SET status = 'PUBLISHED' WHERE event_id = ?", eventId);
        jdbcTemplate.update("UPDATE winner_management SET status = 'DECLINED' WHERE winner_id = ?",
                originalWinner.getId());

        Long requestId = redrawRequestCreateService.create(new RedrawRequestCreateCommand(
                adminId, eventId, "당첨 포기", UUID.randomUUID().toString())).redrawRequestId();
        redrawRequestReviewService.approve(adminId, requestId);

        RedrawRequestExecutionResult failed = redrawRequestExecutionService.execute(adminId, requestId);

        assertThat(failed.executionStatus()).isEqualTo(RedrawExecutionStatus.FAILED);
        assertThat(failed.redrawDrawingId()).isNotNull();
        Drawing failedDrawing = drawingRepository.findById(failed.redrawDrawingId()).orElseThrow();
        Long seedId = failedDrawing.getSeedId();
        assertThat(failedDrawing.getStatus()).isEqualTo(DrawingStatus.FAILED);
        assertThat(seedRepository.findById(seedId)).isPresent();
        assertThat(failedDrawing.getInputPayload()).startsWith("CKING_DRAW_INPUT_V2\n");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_attempt_history WHERE drawing_id = ? AND status = 'FAILED'",
                Integer.class, failedDrawing.getId())).isOne();
        assertThat(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(failedDrawing.getId())).isEmpty();
        assertThatThrownBy(() -> redrawRequestCreateService.create(new RedrawRequestCreateCommand(
                adminId, eventId, "같은 결원 중복 요청", UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", RedrawErrorCode.NO_REDRAW_VACANCY);

        DrawingRetryResult retried = drawingRetryService.retry(failedDrawing.getId(), adminId);

        Drawing completed = drawingRepository.findById(retried.drawingId()).orElseThrow();
        assertThat(completed.getId()).isEqualTo(failedDrawing.getId());
        assertThat(completed.getSeedId()).isEqualTo(seedId);
        assertThat(completed.getStatus()).isEqualTo(DrawingStatus.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(2);
        List<Winner> redrawWinners = winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(completed.getId());
        assertThat(redrawWinners).hasSize(1);
        assertThat(redrawWinners.getFirst().getMemberId()).isNotEqualTo(originalWinner.getMemberId());
        assertThat(redrawWinners.getFirst().getSnapshotPrizeId()).isEqualTo(originalWinner.getSnapshotPrizeId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM redraw_request WHERE id = ?", String.class, requestId))
                .isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT execution_status FROM redraw_execution_history WHERE redraw_request_id = ? ORDER BY id",
                String.class, requestId)).containsExactly("FAILED", "EXECUTED");
    }

    private Member saveMember(String name, MemberRole role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.saveAndFlush(new Member(
                name, "010-" + unique.substring(0, 4) + "-" + unique.substring(4), unique + "@cking.test", role));
        memberIds.add(member.getMemberId());
        return member;
    }

    private EventEntry entry(Long memberId, long ticketCount) {
        return EventEntry.builder()
                .memberId(memberId)
                .eventId(eventId)
                .requestId(UUID.randomUUID().toString())
                .usedTicketCount(ticketCount)
                .appliedAt(Instant.parse("2026-09-14T00:00:00Z"))
                .build();
    }

    @TestConfiguration
    static class FailFirstRedrawEngineConfiguration {

        @Bean
        @Primary
        DrawingEngine failFirstRedrawEngine() {
            DrawingEngine delegate = new ResolvingDrawingEngine(Map.of(
                    DrawingAlgorithmVersion.UNIFORM_V1, new UniformV1DrawingEngine(),
                    DrawingAlgorithmVersion.WEIGHTED_V1, new WeightedV1DrawingEngine()));
            AtomicBoolean failed = new AtomicBoolean();
            return new DrawingEngine() {
                @Override
                public DrawOutput draw(DrawInput input) {
                    if (!input.excludedMemberIds().isEmpty() && failed.compareAndSet(false, true)) {
                        throw new IllegalStateException("REDRAW 엔진 일시 장애");
                    }
                    return delegate.draw(input);
                }
            };
        }
    }
}
