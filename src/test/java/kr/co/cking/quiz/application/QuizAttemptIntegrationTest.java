package kr.co.cking.quiz.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** CI 전용 실제 MySQL 검증. 생성기·Ticket·Redis·Provider를 사용하지 않는다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({FanQuizAttemptService.class,QuizAttemptStore.class,QuizRevisionReader.class,QuizAttemptSnapshotReader.class,QuizAttemptIntegrationTest.Configuration.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class QuizAttemptIntegrationTest {
    @Autowired FanQuizAttemptService service;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired QuizRepository quizzes;
    @Autowired QuizRevisionRepository revisions;
    @Autowired QuizQuestionRepository questions;
    @Autowired QuizOptionRepository options;
    @MockitoSpyBean QuizAttemptAnswerRepository answers;
    /** Ticket·Redis를 쓰지 않는다(이슈 #543의 보상 전달은 별도 테스트가 검증한다). */
    @MockitoBean QuizRewardDelivery rewardDelivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager manager;
    Member fan,owner; Creator creator; Quiz quiz; QuizRevision revision;
    UUID request=UUID.randomUUID();
    @BeforeEach void setup() {
        fan=members.saveAndFlush(new Member("fan-"+UUID.randomUUID().toString().substring(0,8),null,null,MemberRole.USER));
        owner=members.saveAndFlush(new Member("owner-"+UUID.randomUUID().toString().substring(0,8),null,null,MemberRole.USER));
        creator=creators.saveAndFlush(new Creator(owner.getMemberId(),"creator"));
        quiz=quizzes.saveAndFlush(new Quiz(creator.getCreatorId(),QuizFixtures.VIDEO_ID,QuizFixtures.CONTENT_TEXT,QuizFixtures.NOW));
        revision=revisions.saveAndFlush(new QuizRevision(quiz.getQuizId(),1,QuizFixtures.TITLE,QuizFixtures.PROMPT_VERSION,2,2,
            QuizRevisionFingerprint.revisionSha256(quiz.getContentTextSha256(),QuizFixtures.TITLE,QuizFixtures.PROMPT_VERSION,QuizFixtures.fingerprintQuestions()),QuizFixtures.NOW));
        for(int i=0;i<2;i++) {
            var q=QuizFixtures.questions().get(i);
            var row=questions.saveAndFlush(new QuizQuestion(revision.getRevisionId(),i,q.question(),q.correctOptionIndex(),q.explanation(),q.sourceEvidence()));
            for(int o=0;o<2;o++) options.saveAndFlush(new QuizOption(row.getQuestionId(),o,q.options().get(o)));
        }
        quiz.publish(revision.getRevisionId(),revision.getRevisionSha256(),QuizFixtures.NOW);quizzes.saveAndFlush(quiz);
    }
    @AfterEach void cleanup() {
        Long creatorId=creator.getCreatorId();
        jdbc.update("DELETE a FROM quiz_attempt_answer a JOIN quiz_attempt t ON a.attempt_id=t.attempt_id WHERE t.creator_id=?",creatorId);
        jdbc.update("DELETE FROM quiz_attempt WHERE creator_id=?",creatorId);
        jdbc.update("UPDATE quiz SET publication_status='NOT_PUBLISHED',published_revision_id=NULL,content_locked=FALSE,locked_revision_id=NULL,locked_revision_sha256=NULL WHERE creator_id=?",creatorId);
        jdbc.update("DELETE o FROM quiz_option o JOIN quiz_question q ON o.question_id=q.question_id JOIN quiz_revision r ON q.revision_id=r.revision_id JOIN quiz z ON r.quiz_id=z.quiz_id WHERE z.creator_id=?",creatorId);
        jdbc.update("DELETE q FROM quiz_question q JOIN quiz_revision r ON q.revision_id=r.revision_id JOIN quiz z ON r.quiz_id=z.quiz_id WHERE z.creator_id=?",creatorId);
        jdbc.update("DELETE r FROM quiz_revision r JOIN quiz z ON r.quiz_id=z.quiz_id WHERE z.creator_id=?",creatorId);
        jdbc.update("DELETE FROM quiz WHERE creator_id=?",creatorId);creators.delete(creator);members.deleteAll(List.of(fan,owner));
    }
    private Quiz anotherPublishedQuiz() {
        var q=quizzes.saveAndFlush(new Quiz(creator.getCreatorId(),QuizFixtures.VIDEO_ID,QuizFixtures.CONTENT_TEXT,QuizFixtures.NOW));
        var r=revisions.saveAndFlush(new QuizRevision(q.getQuizId(),1,QuizFixtures.TITLE,QuizFixtures.PROMPT_VERSION,2,2,revision.getRevisionSha256(),QuizFixtures.NOW));
        for(int i=0;i<2;i++) {
            var original=QuizFixtures.questions().get(i);var row=questions.saveAndFlush(new QuizQuestion(r.getRevisionId(),i,original.question(),original.correctOptionIndex(),original.explanation(),original.sourceEvidence()));
            for(int o=0;o<2;o++) options.saveAndFlush(new QuizOption(row.getQuestionId(),o,original.options().get(o)));
        }
        q.publish(r.getRevisionId(),r.getRevisionSha256(),QuizFixtures.NOW);return quizzes.saveAndFlush(q);
    }
    QuizAttemptCommand command(UUID id) { return new QuizAttemptCommand(fan.getMemberId(),creator.getCreatorId(),quiz.getQuizId(),id,revision.getRevisionId(),List.of(new QuizAttemptCommand.Answer(0,0),new QuizAttemptCommand.Answer(1,1))); }
    @Test void 실제_저장_replay_및_게시취소후_스냅샷을_보존한다() {
        var result=service.submit(command(request));assertThat(result.correctCount()).isEqualTo(2);
        new TransactionTemplate(manager).executeWithoutResult(s->{var q=quizzes.findByIdForUpdate(quiz.getQuizId()).orElseThrow();q.unpublish(QuizFixtures.NOW);});
        assertThat(service.submit(command(request))).isEqualTo(result);
        assertThat(service.findMine(fan.getMemberId(),creator.getCreatorId(),quiz.getQuizId())).isEqualTo(result);
        assertThatThrownBy(()->service.submit(command(UUID.randomUUID()))).isInstanceOf(BusinessException.class);
    }
    @Test void 동일_요청_동시_제출은_같은_결과와_한_행이다() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Long> work=()->{start.await();return service.submit(command(request)).attemptId();};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(b.get(30,TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_id=?",Integer.class,quiz.getQuizId())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void 헤더_INSERT후_답안_CHECK_실패는_전체_롤백한다() {
        doAnswer(invocation->{
            List<QuizAttemptAnswer> rows=invocation.getArgument(0);
            assertThat(((Number)entityManager.createNativeQuery("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_id=?1")
                .setParameter(1,quiz.getQuizId()).getSingleResult()).intValue()).isEqualTo(1);
            ReflectionTestUtils.setField(rows.get(0),"selectedOptionIndex",99);
            try {
                rows.forEach(entityManager::persist);
                entityManager.flush();
            } catch (PersistenceException failure) {
                var translated=new HibernateJpaDialect().translateExceptionIfPossible(failure);
                if(translated!=null) throw translated;
                throw failure;
            }
            return rows;
        })
            .when(answers).saveAllAndFlush(any());
        assertThatThrownBy(()->service.submit(command(request))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_id=?",Integer.class,quiz.getQuizId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt_answer a JOIN quiz_attempt t ON a.attempt_id=t.attempt_id WHERE t.quiz_id=?",Integer.class,quiz.getQuizId())).isZero();
    }
    @Test void 답안_UNIQUE와_revision_소속_FK가_실제로_차단한다() {
        var result=service.submit(command(request));
        assertThatThrownBy(()->jdbc.update("INSERT INTO quiz_attempt_answer (attempt_id,question_no,question_text,options_json,option_count,selected_option_index,correct_option_index,correct,explanation,source_evidence) SELECT attempt_id,question_no,question_text,options_json,option_count,selected_option_index,correct_option_index,correct,explanation,source_evidence FROM quiz_attempt_answer WHERE attempt_id=? AND question_no=0",result.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE quiz_attempt SET revision_id=? WHERE attempt_id=?",Long.MAX_VALUE,result.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE quiz_attempt SET quiz_id=? WHERE attempt_id=?",Long.MAX_VALUE,result.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void 스냅샷_내용_변조는_본인_조회에서도_차단한다() {
        var result=service.submit(command(request));jdbc.update("UPDATE quiz_attempt_answer SET explanation='변조' WHERE attempt_id=? AND question_no=0",result.attemptId());
        assertThatThrownBy(()->service.findMine(fan.getMemberId(),creator.getCreatorId(),quiz.getQuizId())).isInstanceOf(BusinessException.class);
    }
    @Test void 게시_취소가_잠금을_먼저_잡으면_신규_제출은_404다() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var cancel=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{var q=quizzes.findByIdForUpdate(quiz.getQuizId()).orElseThrow();q.unpublish(QuizFixtures.NOW);locked.countDown();try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("대기 초과");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var submit=pool.submit(()->service.submit(command(request)));release.countDown();cancel.get(30,TimeUnit.SECONDS);
            assertThatThrownBy(()->submit.get(30,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void 같은_Member_UUID가_다른_Quiz에서_경쟁해도_UNIQUE로_한_번만_저장한다() throws Exception {
        var another=anotherPublishedQuiz();var second=new QuizAttemptCommand(fan.getMemberId(),creator.getCreatorId(),another.getQuizId(),request,another.getPublishedRevisionId(),command(request).answers());
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(pool.submit(()->{start.await();return service.submit(command(request));}),pool.submit(()->{start.await();return service.submit(second);}));start.countDown();
            int success=0,conflict=0;
            for(var future:futures) {
                try {future.get(30,TimeUnit.SECONDS);success++;} catch(ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(BusinessException.class,b->assertThat(b.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_ATTEMPT_REQUEST_CONFLICT));conflict++;
                }
            }
            assertThat(success).isEqualTo(1);assertThat(conflict).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE member_id=? AND request_id=?",Integer.class,fan.getMemberId(),request.toString())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void 다른_UUID의_동일_Quiz_경쟁은_재응시_충돌이다() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var futures=List.of(pool.submit(()->{start.await();return service.submit(command(request));}),pool.submit(()->{start.await();return service.submit(command(UUID.randomUUID()));}));start.countDown();
            int success=0,conflict=0;
            for(var future:futures) {
                try {future.get(30,TimeUnit.SECONDS);success++;}catch(ExecutionException e){assertThat(e.getCause()).isInstanceOfSatisfying(BusinessException.class,b->assertThat(b.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_ALREADY_ATTEMPTED));conflict++;}
            }
            assertThat(success).isEqualTo(1);assertThat(conflict).isEqualTo(1);
        }finally {pool.shutdownNow();}
    }
    @TestConfiguration static class Configuration {
        @Bean Clock clock(){return Clock.fixed(QuizFixtures.NOW,ZoneOffset.UTC);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
    }
}
