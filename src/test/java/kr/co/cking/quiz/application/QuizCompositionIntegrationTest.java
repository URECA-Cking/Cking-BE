package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.dto.*;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CI MySQL 전용. Provider·Redis Worker를 호출하지 않고 실제 접수 트랜잭션·UNIQUE·rollback을 검증한다. */
@SpringBootTest
class QuizCompositionIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired CreatorQuizCompositionService service;
    @Autowired CreatorQuizCommandService commands;
    @Autowired QuizCompositionMetadataReader metadata;
    @Autowired QuizDraftService drafts;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired QuizQuestionRepository questions;
    @Autowired QuizRevisionRepository revisions;
    @Autowired QuizAttemptStore attemptStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean QuizCompositionRequestRepository requests;
    QuizIntegrationData data;
    Creator creator;
    QuizDraft draft;
    List<QuizQuestion> originals;
    @BeforeEach void setup(){
        data=new QuizIntegrationData(members,creators,drafts,jdbc);creator=data.creator();
        draft=new QuizJudgeIntegrationFixture(context).draft(creator);
        originals=questions.findByRevisionIdOrderByQuestionNoAsc(draft.revisionId());
    }
    QuizCompositionCommand command(UUID id,List<QuizCompositionCommand.Selection> q){
        return new QuizCompositionCommand(id,draft.revisionId(),revisions.findById(draft.revisionId()).orElseThrow().getRevisionSha256(),q);
    }
    List<QuizCompositionCommand.Selection> copy(){return List.of(new QuizCompositionCommand.Selection(originals.get(0).getQuestionId(),null));}
    @AfterEach void cleanup(){
        reset(requests);
        if(creator==null)return;
        jdbc.update("UPDATE quiz SET publication_status='NOT_PUBLISHED',published_revision_id=NULL,content_locked=FALSE,locked_revision_id=NULL,locked_revision_sha256=NULL WHERE creator_id=?",creator.getCreatorId());
        jdbc.update("DELETE a FROM quiz_attempt_answer a JOIN quiz_attempt t ON a.attempt_id=t.attempt_id WHERE t.creator_id=?",creator.getCreatorId());
        jdbc.update("DELETE FROM quiz_attempt WHERE creator_id=?",creator.getCreatorId());
        jdbc.update("DELETE c FROM quiz_composition_request c JOIN quiz q ON c.quiz_id=q.quiz_id WHERE q.creator_id=?",creator.getCreatorId());
        var ids=jdbc.queryForList("SELECT r.revision_id FROM quiz_revision r JOIN quiz q ON r.quiz_id=q.quiz_id WHERE q.creator_id=? AND r.origin='CREATOR_COMPOSED' ORDER BY r.revision_no DESC",Long.class,creator.getCreatorId());
        for(var id:ids){
            jdbc.update("DELETE FROM quiz_revision_decision WHERE revision_id=?",id);
            jdbc.update("DELETE o FROM quiz_option o JOIN quiz_question q ON o.question_id=q.question_id WHERE q.revision_id=?",id);
            jdbc.update("DELETE FROM quiz_question WHERE revision_id=?",id);
            jdbc.update("DELETE FROM quiz_revision WHERE revision_id=?",id);
        }
        data.cleanUp();
    }
    @Test void 동일요청_경쟁은_한_revision과_replay로_수렴한다() throws Exception{
        var cmd=command(UUID.randomUUID(),copy());var gate=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<QuizCompositionStore.Admission> task=()->{gate.await(10,TimeUnit.SECONDS);return service.compose(creator.getMemberId(),draft.quizId(),cmd);};
            var a=pool.submit(task);var b=pool.submit(task);var first=a.get(30,TimeUnit.SECONDS);var second=b.get(30,TimeUnit.SECONDS);
            assertThat(first.revisionId()).isEqualTo(second.revisionId());assertThat(first.created()).isNotEqualTo(second.created());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_composition_request WHERE quiz_id=?",Integer.class,draft.quizId())).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void 다른_UUID_같은_base_경쟁은_한번만_저장한다() throws Exception{
        var gate=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Object> task=()->{var cmd=command(UUID.randomUUID(),copy());gate.await(10,TimeUnit.SECONDS);try{return service.compose(creator.getMemberId(),draft.quizId(),cmd);}catch(BusinessException e){return e.getErrorCode();}};
            var a=pool.submit(task);var b=pool.submit(task);var values=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertThat(values.stream().filter(v->v instanceof QuizCompositionStore.Admission)).hasSize(1);
            assertThat(values).contains(QuizErrorCode.QUIZ_REVISION_CONFLICT);
        }finally{pool.shutdownNow();}
    }
    @Test void 접수_UNIQUE_실패시_revision_문항_접수가_함께_rollback된다(){
        var cmd=command(UUID.randomUUID(),copy());
        doAnswer(i->{
            var r=(QuizCompositionRequest)i.getArgument(0);entityManager.flush();
            String sql="INSERT INTO quiz_composition_request(quiz_id,request_id,payload_sha256,revision_id,requested_by_member_id,created_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6))";
            jdbc.update(sql,r.getQuizId(),r.getRequestId(),r.getPayloadSha256(),r.getRevisionId(),r.getRequestedByMemberId());
            jdbc.update(sql,r.getQuizId(),r.getRequestId(),r.getPayloadSha256(),r.getRevisionId(),r.getRequestedByMemberId());
            return r;
        }).when(requests).saveAndFlush(any());
        assertThatThrownBy(()->service.compose(creator.getMemberId(),draft.quizId(),cmd)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_revision WHERE quiz_id=?",Integer.class,draft.quizId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_composition_request WHERE quiz_id=?",Integer.class,draft.quizId())).isZero();
        assertThat(questions.findByRevisionIdOrderByQuestionNoAsc(draft.revisionId())).hasSize(3);
    }
    @Test void 책임승인_게시_취소후_편집금지와_과거요청_replay를_유지한다(){
        var cmd=command(UUID.randomUUID(),copy());var admission=service.compose(creator.getMemberId(),draft.quizId(),cmd);
        var hash=revisions.findById(admission.revisionId()).orElseThrow().getRevisionSha256();
        commands.approveByCreator(creator.getMemberId(),draft.quizId(),admission.revisionId(),hash,true);
        commands.publish(creator.getMemberId(),draft.quizId(),admission.revisionId());commands.unpublish(creator.getMemberId(),draft.quizId());
        assertThat(service.compose(creator.getMemberId(),draft.quizId(),cmd).revisionId()).isEqualTo(admission.revisionId());
        assertThatThrownBy(()->service.compose(creator.getMemberId(),draft.quizId(),command(UUID.randomUUID(),copy())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_CONTENT_LOCKED));
        commands.publish(creator.getMemberId(),draft.quizId(),admission.revisionId());
    }
    @Test void FAIL_원본을_수정없이_선택해도_판정을_보존하고_책임승인한다(){
        new QuizJudgeIntegrationFixture(context).saveResult(creator,draft,kr.co.cking.quiz.judge.JudgeVerdict.FAIL);
        var a=service.compose(creator.getMemberId(),draft.quizId(),command(UUID.randomUUID(),copy()));
        var view=metadata.read(draft.quizId(),a.revisionId());
        assertThat(view.questions().getFirst().evaluationStatus()).isEqualTo("FAIL");
        assertThat(view.questions().getFirst().sourceEvaluation().items().values()).allMatch(i->i.verdict().equals("fail"));
        commands.approveByCreator(creator.getMemberId(),draft.quizId(),a.revisionId(),view.revisionSha256(),true);
        commands.publish(creator.getMemberId(),draft.quizId(),a.revisionId());
    }
    @Test void 최종_1부터5문항의_Fan_채점과_보상_snapshot을_유지한다(){
        var fan=data.creator();
        for(int count=1;count<=5;count++){
            if(count>1){draft=new QuizJudgeIntegrationFixture(context).draft(creator);originals=questions.findByRevisionIdOrderByQuestionNoAsc(draft.revisionId());}
            var selections=new ArrayList<QuizCompositionCommand.Selection>();
            for(int i=0;i<count;i++) selections.add(new QuizCompositionCommand.Selection(null,new QuizCompositionCommand.Content("문제 "+i,List.of("A","B","C","D"),0,"해설",null)));
            var a=service.compose(creator.getMemberId(),draft.quizId(),command(UUID.randomUUID(),selections));
            var hash=revisions.findById(a.revisionId()).orElseThrow().getRevisionSha256();commands.approveByCreator(creator.getMemberId(),draft.quizId(),a.revisionId(),hash,true);commands.publish(creator.getMemberId(),draft.quizId(),a.revisionId());
            var answers=java.util.stream.IntStream.range(0,count).mapToObj(i->new QuizAttemptCommand.Answer(i,0)).toList();
            var result=attemptStore.submit(new QuizAttemptCommand(fan.getMemberId(),creator.getCreatorId(),draft.quizId(),UUID.randomUUID(),a.revisionId(),answers));
            assertThat(result.correctCount()).isEqualTo(count);assertThat(result.reward().amount()).isEqualTo(count);
            assertThat(result.questions()).allMatch(q->q.sourceEvidence().isEmpty());assertThat(result.reward().requestId()).isNotNull();
        }
    }
}
