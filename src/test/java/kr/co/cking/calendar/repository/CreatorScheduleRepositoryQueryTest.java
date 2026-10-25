package kr.co.cking.calendar.repository;

import kr.co.cking.calendar.application.CreatorScheduleQueryService;
import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CreatorScheduleRepositoryQueryTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorScheduleRepository scheduleRepository;

    @Test
    void 기간_조회는_겹치는_일정만_시작시각_오름차순으로_반환하고_다른_크리에이터_일정은_제외한다() {
        Member member = memberRepository.saveAndFlush(new Member("캘린더 조회 크리에이터", null, null, MemberRole.USER));
        Member otherMember = memberRepository.saveAndFlush(new Member("캘린더 조회 다른 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "캘린더 조회 크리에이터"));
        Creator otherCreator = creatorRepository.saveAndFlush(new Creator(otherMember.getMemberId(), "캘린더 조회 다른 크리에이터"));

        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");

        CreatorSchedule before = save(creator, "범위 이전", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"));
        CreatorSchedule overlapStart = save(creator, "시작 걸침", Instant.parse("2026-09-30T23:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"));
        CreatorSchedule inside = save(creator, "완전 포함", Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T01:00:00Z"));
        CreatorSchedule overlapEnd = save(creator, "종료 걸침", Instant.parse("2026-10-30T23:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
        CreatorSchedule after = save(creator, "범위 이후", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"));
        save(otherCreator, "다른 크리에이터", Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T01:00:00Z"));

        List<CreatorSchedule> result = scheduleRepository.findByCreatorIdAndRange(creator.getCreatorId(), from, to);

        assertThat(result).extracting(CreatorSchedule::getScheduleId)
                .containsExactly(
                        overlapStart.getScheduleId(), inside.getScheduleId(), overlapEnd.getScheduleId());
        assertThat(result).extracting(CreatorSchedule::getTitle)
                .doesNotContain(before.getTitle(), after.getTitle());
    }

    @Test
    void 다섯_자리_연도로_조회해도_DB_오류_없이_빈_목록이다() {
        Member member = memberRepository.saveAndFlush(new Member("먼 미래 조회 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "먼 미래 조회 크리에이터"));
        CreatorScheduleQueryService service = new CreatorScheduleQueryService(creatorRepository, scheduleRepository);

        assertThat(service.findByCreatorId(creator.getCreatorId(),
                Instant.parse("+10000-01-01T00:00:00Z"), Instant.parse("+10000-01-02T00:00:00Z"))).isEmpty();
    }

    @Test
    void 저장_범위를_걸친_기존_일정은_겹치는_조회에만_나오고_범위_밖_조회에는_나오지_않는다() {
        Member member = memberRepository.saveAndFlush(new Member("경계 일정 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "경계 일정 크리에이터"));
        CreatorSchedule crossing = save(creator, "범위를 걸친 기존 일정",
                Instant.parse("2099-12-31T00:00:00Z"), Instant.parse("2101-01-01T00:00:00Z"));
        CreatorScheduleQueryService service = new CreatorScheduleQueryService(creatorRepository, scheduleRepository);

        assertThat(service.findByCreatorId(creator.getCreatorId(),
                Instant.parse("2101-06-01T00:00:00Z"), Instant.parse("2101-06-02T00:00:00Z"))).isEmpty();
        assertThat(service.findByCreatorId(creator.getCreatorId(),
                Instant.parse("2099-12-01T00:00:00Z"), Instant.parse("2100-01-30T00:00:00Z")))
                .extracting(CreatorSchedule::getScheduleId).containsExactly(crossing.getScheduleId());
    }

    private CreatorSchedule save(Creator creator, String title, Instant startAt, Instant endAt) {
        return scheduleRepository.saveAndFlush(new CreatorSchedule(
                creator.getCreatorId(), ScheduleType.OTHER, title, null, startAt, endAt, false,
                "Asia/Seoul", null, null, null, Instant.now()));
    }
}
