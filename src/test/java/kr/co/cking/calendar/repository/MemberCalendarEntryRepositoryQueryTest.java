package kr.co.cking.calendar.repository;

import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.MemberCalendarEntry;
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
class MemberCalendarEntryRepositoryQueryTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorScheduleRepository scheduleRepository;

    @Autowired
    private MemberCalendarEntryRepository entryRepository;

    @Test
    void 기간_조회는_담은_일정_중_겹치는_것만_크리에이터_이름과_함께_시작시각_오름차순으로_반환한다() {
        Member fan = memberRepository.saveAndFlush(new Member("담기 조회 팬", null, null, MemberRole.USER));
        Member otherFan = memberRepository.saveAndFlush(new Member("담기 조회 다른 팬", null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(new Member("담기 조회 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "담기 조회 크리에이터"));

        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");

        CreatorSchedule before = save(creator, "범위 이전", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"));
        CreatorSchedule inside = save(creator, "완전 포함", Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T01:00:00Z"));
        CreatorSchedule notEntered = save(creator, "담지 않은 일정", Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-15T01:00:00Z"));

        entryRepository.saveAndFlush(new MemberCalendarEntry(fan.getMemberId(), before.getScheduleId(), Instant.now()));
        entryRepository.saveAndFlush(new MemberCalendarEntry(fan.getMemberId(), inside.getScheduleId(), Instant.now()));
        entryRepository.saveAndFlush(new MemberCalendarEntry(otherFan.getMemberId(), notEntered.getScheduleId(), Instant.now()));

        List<MemberCalendarScheduleProjection> result =
                entryRepository.findSchedulesByMemberIdAndRange(fan.getMemberId(), from, to);

        assertThat(result).extracting(MemberCalendarScheduleProjection::scheduleId)
                .containsExactly(inside.getScheduleId());
        assertThat(result.get(0).creatorName()).isEqualTo("담기 조회 크리에이터");
        assertThat(result.get(0).creatorId()).isEqualTo(creator.getCreatorId());
    }

    @Test
    void 같은_회원이_같은_일정을_두_번_담을_수_없다() {
        Member fan = memberRepository.saveAndFlush(new Member("중복 담기 팬", null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(new Member("중복 담기 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "중복 담기 크리에이터"));
        CreatorSchedule schedule = save(creator, "중복 담기 대상", Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T01:00:00Z"));
        entryRepository.saveAndFlush(new MemberCalendarEntry(fan.getMemberId(), schedule.getScheduleId(), Instant.now()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        entryRepository.saveAndFlush(new MemberCalendarEntry(fan.getMemberId(), schedule.getScheduleId(), Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 크리에이터가_일정을_하드_삭제하면_담긴_참조도_cascade로_함께_삭제된다() {
        Member fan = memberRepository.saveAndFlush(new Member("cascade 팬", null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(new Member("cascade 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "cascade 크리에이터"));
        CreatorSchedule schedule = save(creator, "삭제될 일정", Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T01:00:00Z"));
        MemberCalendarEntry entry = entryRepository.saveAndFlush(
                new MemberCalendarEntry(fan.getMemberId(), schedule.getScheduleId(), Instant.now()));

        scheduleRepository.delete(schedule);
        scheduleRepository.flush();

        assertThat(entryRepository.existsById(entry.getEntryId())).isFalse();
    }

    private CreatorSchedule save(Creator creator, String title, Instant startAt, Instant endAt) {
        return scheduleRepository.saveAndFlush(new CreatorSchedule(
                creator.getCreatorId(), ScheduleType.OTHER, title, null, startAt, endAt,
                "Asia/Seoul", null, null, null, Instant.now()));
    }
}
