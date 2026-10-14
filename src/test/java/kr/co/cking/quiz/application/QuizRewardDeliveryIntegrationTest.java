package kr.co.cking.quiz.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.application.dto.QuizRewardPaymentStatus;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import kr.co.cking.ticket.application.*;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CI 전용 실제 MySQL 검증(이슈 #543). 응시 저장 커밋 → Ticket 접수·실행권·결과 기록까지 실제 DB로 확인한다. Redis 실행·
 * Balance 키 확인·적재만 mock이며 Provider는 쓰지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({FanQuizAttemptService.class,QuizAttemptStore.class,QuizRevisionReader.class,QuizAttemptSnapshotReader.class,
        QuizRewardDelivery.class,TicketQuizRewardService.class,QuizRewardRequestStore.class,
        QuizRewardExecutionClaimService.class,QuizRewardExecutionStore.class,QuizRewardEarnWorker.class,
        QuizRewardDeliveryIntegrationTest.Configuration.class})
@TestPropertySource(properties="cking.ticket.quiz-reward.worker-enabled=true")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class QuizRewardDeliveryIntegrationTest {
    @Autowired FanQuizAttemptService service;
    @Autowired QuizRewardDelivery delivery;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired QuizRepository quizzes;
    @Autowired QuizRevisionRepository revisions;
    @Autowired QuizQuestionRepository questions;
    @Autowired QuizOptionRepository options;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean QuizAttemptAnswerRepository answers;
    @MockitoSpyBean TicketQuizRewardService rewardService;
    @MockitoBean QuizTicketEarnRedisExecutor redisExecutor;
    @MockitoBean TicketBalanceKeyLoader balanceKeyLoader;
    @MockitoBean StringRedisTemplate redisTemplate;
    Member fan,owner; Creator creator; Quiz quiz; QuizRevision revision;

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
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisExecutor.execute(any())).thenReturn(new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED,"1-0",2L,null,null));
    }
    @AfterEach void cleanup() {
        Long creatorId=creator.getCreatorId();
        List<String> rewardIds=jdbc.queryForList("SELECT reward_request_id FROM quiz_attempt WHERE creator_id=? AND reward_request_id IS NOT NULL",String.class,creatorId);
        jdbc.update("DELETE e FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r ON e.quiz_reward_request_id=r.quiz_reward_request_id WHERE r.creator_id=?",creatorId);
        jdbc.update("DELETE FROM ticket_quiz_reward_request WHERE creator_id=?",creatorId);
        for(String id:rewardIds) jdbc.update("DELETE FROM ticket_earn_request WHERE request_id=?",id);
        jdbc.update("DELETE a FROM quiz_attempt_answer a JOIN quiz_attempt t ON a.attempt_id=t.attempt_id WHERE t.creator_id=?",creatorId);
        jdbc.update("DELETE FROM quiz_attempt WHERE creator_id=?",creatorId);
        jdbc.update("UPDATE quiz SET publication_status='NOT_PUBLISHED',published_revision_id=NULL,content_locked=FALSE,locked_revision_id=NULL,locked_revision_sha256=NULL WHERE creator_id=?",creatorId);
        jdbc.update("DELETE o FROM quiz_option o JOIN quiz_question q ON o.question_id=q.question_id JOIN quiz_revision r ON q.revision_id=r.revision_id JOIN quiz z ON r.quiz_id=z.quiz_id WHERE z.creator_id=?",creatorId);
        jdbc.update("DELETE q FROM quiz_question q JOIN quiz_revision r ON q.revision_id=r.revision_id JOIN quiz z ON r.quiz_id=z.quiz_id WHERE z.creator_id=?",creatorId);
        jdbc.update("DELETE r FROM quiz_revision r JOIN quiz z ON r.quiz_id=z.quiz_id WHERE z.creator_id=?",creatorId);
        jdbc.update("DELETE FROM quiz WHERE creator_id=?",creatorId);creators.delete(creator);members.deleteAll(List.of(fan,owner));
    }
    /** 두 문항의 정답은 0, 1번 보기다. */
    QuizAttemptCommand command(UUID id,int firstAnswer,int secondAnswer) {
        return new QuizAttemptCommand(fan.getMemberId(),creator.getCreatorId(),quiz.getQuizId(),id,revision.getRevisionId(),
            List.of(new QuizAttemptCommand.Answer(0,firstAnswer),new QuizAttemptCommand.Answer(1,secondAnswer)));
    }
    QuizAttemptCommand command(UUID id) { return command(id,0,1); }
    String ticketStatus(UUID rewardId) {
        return jdbc.queryForList("SELECT status FROM ticket_quiz_reward_request WHERE reward_request_id=?",String.class,rewardId.toString())
            .stream().findFirst().orElse(null);
    }
    int executions(UUID rewardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r ON e.quiz_reward_request_id=r.quiz_reward_request_id WHERE r.reward_request_id=?",Integer.class,rewardId.toString());
    }

    @Test void 커밋된_응시는_저장된_rewardRequestId로_접수_실행되고_응답에_상태가_합성된다() {
        var result=service.submit(command(UUID.randomUUID()));

        UUID stored=UUID.fromString(jdbc.queryForObject("SELECT reward_request_id FROM quiz_attempt WHERE attempt_id=?",String.class,result.attemptId()));
        assertThat(result.reward().requestId()).isEqualTo(stored);
        assertThat(ticketStatus(stored)).isEqualTo("ACCEPTED");
        assertThat(result.reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.PENDING);
        assertThat(result.reward().status()).isEqualTo("NOT_INTEGRATED");
        assertThat(jdbc.queryForObject("SELECT reward_status FROM quiz_attempt WHERE attempt_id=?",String.class,result.attemptId())).isEqualTo("NOT_INTEGRATED");
        // 반영 완료를 흉내 내 APPLIED가 PAID로 보이는지 확인한다(Ticket Consumer 경로는 Ticket 테스트가 검증한다).
        jdbc.update("UPDATE ticket_quiz_reward_request SET status='APPLIED',applied_at=UTC_TIMESTAMP(6) WHERE reward_request_id=?",stored.toString());
        assertThat(service.findMine(fan.getMemberId(),creator.getCreatorId(),quiz.getQuizId()).reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.PAID);
    }

    @Test void 응시_저장이_롤백되면_Ticket_요청을_만들지_않는다() {
        doThrow(new DataIntegrityViolationException("answer check")).when(answers).saveAllAndFlush(any());

        assertThatThrownBy(()->service.submit(command(UUID.randomUUID()))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_id=?",Integer.class,quiz.getQuizId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_quiz_reward_request WHERE creator_id=?",Integer.class,creator.getCreatorId())).isZero();
        verify(rewardService,never()).admit(any());
    }

    @Test void 커밋_뒤_전달이_실패해도_응시는_남고_재시작_후_재전달로_같은_UUID를_전달한다() {
        doThrow(new QueryTimeoutException("ticket down")).when(rewardService).admit(any());
        var result=service.submit(command(UUID.randomUUID()));
        UUID rewardId=result.reward().requestId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE attempt_id=?",Integer.class,result.attemptId())).isEqualTo(1);
        assertThat(ticketStatus(rewardId)).isNull();
        assertThatThrownBy(()->service.submit(command(UUID.randomUUID()))).isInstanceOf(kr.co.cking.common.exception.BusinessException.class);

        // 재시작: 메모리 커서가 0부터 다시 훑는다.
        reset(rewardService);
        long cursor=0;
        do { cursor=delivery.redeliverAfter(cursor,1); } while(cursor!=0);

        assertThat(ticketStatus(rewardId)).isEqualTo("ACCEPTED");
        assertThat(executions(rewardId)).isEqualTo(1);
        verify(redisExecutor,times(1)).execute(any());
    }

    @Test void 즉시_전달_replay_재전달이_겹쳐도_Redis_실행은_한_번이다() throws Exception {
        UUID request=UUID.randomUUID();
        // 첫 전달을 실패시켜 미접수로 남긴 뒤, replay와 재전달 두 개가 동시에 접수·실행권을 경쟁하게 한다.
        doThrow(new QueryTimeoutException("ticket down")).when(rewardService).admit(any());
        var result=service.submit(command(request));
        reset(rewardService);
        var pool=Executors.newFixedThreadPool(3);var start=new CountDownLatch(1);
        try {
            List<Future<?>> futures=List.of(
                pool.submit(()->{start.await();return service.submit(command(request));}),
                pool.submit(()->{start.await();return delivery.redeliverAfter(0,100);}),
                pool.submit(()->{start.await();return delivery.redeliverAfter(0,100);}));
            start.countDown();
            for(var future:futures) future.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}

        assertThat(executions(result.reward().requestId())).isEqualTo(1);
        verify(redisExecutor,times(1)).execute(any());
    }

    @Test void UNKNOWN과_실행_중_요청은_재전달해도_다시_실행하지_않는다() {
        when(redisExecutor.execute(any())).thenReturn(QuizRedisEarnResult.unknown("TIMEOUT"));
        var result=service.submit(command(UUID.randomUUID()));
        UUID rewardId=result.reward().requestId();
        assertThat(ticketStatus(rewardId)).isEqualTo("UNKNOWN");
        assertThat(result.reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.DELAYED);

        delivery.redeliverAfter(0,100);
        jdbc.update("UPDATE ticket_quiz_reward_request SET status='EXECUTING' WHERE reward_request_id=?",rewardId.toString());
        jdbc.update("UPDATE ticket_quiz_reward_execution e JOIN ticket_quiz_reward_request r ON e.quiz_reward_request_id=r.quiz_reward_request_id SET e.status='STARTED',e.outcome=NULL,e.reason=NULL,e.finished_at=NULL WHERE r.reward_request_id=?",rewardId.toString());
        delivery.redeliverAfter(0,100);

        assertThat(executions(rewardId)).isEqualTo(1);
        verify(redisExecutor,times(1)).execute(any());
    }

    @Test void 정답_0개는_Ticket에_전달하지_않는다() {
        var result=service.submit(command(UUID.randomUUID(),1,0));

        assertThat(result.correctCount()).isZero();
        assertThat(result.reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.NOT_ELIGIBLE);
        delivery.redeliverAfter(0,100);
        verify(rewardService,never()).admit(any());
    }

    @TestConfiguration static class Configuration {
        @Bean Clock clock(){return Clock.fixed(QuizFixtures.NOW,ZoneOffset.UTC);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
    }
}
