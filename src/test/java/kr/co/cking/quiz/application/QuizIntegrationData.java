package kr.co.cking.quiz.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Quiz MySQL 통합 테스트가 만든 Member·Creator·Quiz를 기록하고 테스트 뒤 지운다. */
class QuizIntegrationData {

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final QuizDraftService draftService;
    private final JdbcTemplate jdbcTemplate;
    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();

    QuizIntegrationData(MemberRepository memberRepository, CreatorRepository creatorRepository,
                        QuizDraftService draftService, JdbcTemplate jdbcTemplate) {
        this.memberRepository = memberRepository;
        this.creatorRepository = creatorRepository;
        this.draftService = draftService;
        this.jdbcTemplate = jdbcTemplate;
    }

    Creator creator() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.saveAndFlush(new Member("quiz-" + suffix, null, null, MemberRole.USER));
        members.add(member);
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "creator-" + suffix));
        creators.add(creator);
        return creator;
    }

    QuizDraft draft(Creator creator) {
        return draftService.create(new QuizDraftCommand(creator.getCreatorId(), QuizFixtures.VIDEO_ID,
                QuizFixtures.TITLE, QuizFixtures.input(), QuizFixtures.generated()));
    }

    int quizCount(Creator creator) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz WHERE creator_id = ?", Integer.class,
                creator.getCreatorId());
    }

    int decisionCount(Long revisionId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_revision_decision WHERE revision_id = ?",
                Integer.class, revisionId);
    }

    /** 게시 포인터를 먼저 비워 순환 FK를 풀고, 자식부터 지운다. */
    void cleanUp() {
        for (Creator creator : creators) {
            Long creatorId = creator.getCreatorId();
            jdbcTemplate.update("UPDATE quiz SET publication_status = 'NOT_PUBLISHED', published_revision_id = NULL, "
                    + "content_locked = FALSE, locked_revision_id = NULL, locked_revision_sha256 = NULL "
                    + "WHERE creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE d FROM quiz_revision_decision d JOIN quiz_revision r ON d.revision_id = r.revision_id "
                    + "JOIN quiz q ON r.quiz_id = q.quiz_id WHERE q.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE i FROM quiz_judge_result_item i JOIN quiz_judge_result r ON i.result_id = r.result_id "
                    + "JOIN quiz_judge_job j ON r.job_id = j.job_id WHERE j.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE r FROM quiz_judge_result r JOIN quiz_judge_job j ON r.job_id = j.job_id "
                    + "WHERE j.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE c FROM quiz_judge_job_call c JOIN quiz_judge_job j ON c.job_id = j.job_id "
                    + "WHERE j.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE FROM quiz_judge_job WHERE creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE s FROM quiz_judge_series s JOIN quiz q ON s.quiz_id = q.quiz_id "
                    + "WHERE q.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE o FROM quiz_option o JOIN quiz_question qq ON o.question_id = qq.question_id "
                    + "JOIN quiz_revision r ON qq.revision_id = r.revision_id JOIN quiz q ON r.quiz_id = q.quiz_id "
                    + "WHERE q.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE qq FROM quiz_question qq JOIN quiz_revision r ON qq.revision_id = r.revision_id "
                    + "JOIN quiz q ON r.quiz_id = q.quiz_id WHERE q.creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE r FROM quiz_revision r JOIN quiz q ON r.quiz_id = q.quiz_id WHERE q.creator_id = ?",
                    creatorId);
            jdbcTemplate.update("DELETE FROM quiz WHERE creator_id = ?", creatorId);
        }
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }
}
