package kr.co.cking.quiz.application;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizCompositionMetadata;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 소유권이 확인된 Creator 조회에서만 사용한다. 원본 평가를 검증한 뒤 참고값으로 제공한다. */
@Component @RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizCompositionMetadataReader {
    private final QuizRepository quizzes;
    private final QuizRevisionRepository revisions;
    private final QuizQuestionRepository questions;
    private final QuizRevisionDecisionRepository decisions;
    private final QuizJudgeStoredResultRepository results;
    private final QuizJudgeResultReader judgeReader;
    private final QuizRevisionReader revisionReader;
    public QuizCompositionMetadata read(Long quizId, Long revisionId) {
        var quiz=quizzes.findById(quizId).orElseThrow(this::invalid);
        var revision=revisions.findById(revisionId).filter(r->r.getQuizId().equals(quizId)).orElseThrow(this::invalid);
        revisionReader.readVerified(quiz,revision);
        var sourceCache=new HashMap<Long,Optional<QuizJudgeResultReader.Verified>>();
        var rows=questions.findByRevisionIdOrderByQuestionNoAsc(revisionId);
        var metadata=new ArrayList<QuizCompositionMetadata.Question>();
        for(var row:rows) {
            Long originId=row.getChangeType()==null?row.getQuestionId():row.getOriginQuestionId();
            var origin=originId==null?null:questions.findById(originId).orElseThrow(this::invalid);
            QuizCompositionMetadata.SourceEvaluation source=null;
            String status="NOT_EVALUATED";
            if(origin!=null) {
                var verified=sourceCache.computeIfAbsent(origin.getRevisionId(),id->results.findByRevisionId(id).map(judgeReader::read));
                if(verified.isPresent()) {
                    var v=verified.get();
                    if(!v.target().quizId().equals(quizId)||!v.target().revisionId().equals(origin.getRevisionId())) throw invalid();
                    var judged=v.evaluation().questions().stream().filter(q->q.questionNo()==origin.getQuestionNo()).findFirst().orElseThrow(this::invalid);
                    var items=new LinkedHashMap<String,QuizCompositionMetadata.Item>();
                    judged.items().forEach((rubric,item)->items.put(rubric.field(),new QuizCompositionMetadata.Item(item.verdict().value(),item.reason())));
                    source=new QuizCompositionMetadata.SourceEvaluation(v.result().getResultId(),v.result().getResultSha256(),origin.getRevisionId(),origin.getQuestionNo(),Map.copyOf(items));
                    status=items.values().stream().anyMatch(i->i.verdict().equals("fail"))?"FAIL"
                            :items.values().stream().anyMatch(i->i.verdict().equals("uncertain"))?"UNCERTAIN":"PASS";
                }
            }
            if(row.getChangeType()==QuizQuestionChangeType.CREATOR_ADDED) status="NOT_EVALUATED";
            else if(hasEditedHistory(row)) status="NOT_REVALIDATED";
            metadata.add(new QuizCompositionMetadata.Question(row.getQuestionId(),row.getQuestionNo(),originId,
                    row.getPreviousQuestionId(),row.getChangeType(),status,source));
        }
        var decision=decisions.findByRevisionId(revisionId).orElse(null);
        boolean latest = revisions.findTopByQuizIdOrderByRevisionNoDesc(quizId)
                .map(r -> r.getRevisionId().equals(revisionId)).orElse(false);
        return new QuizCompositionMetadata(revision.getOrigin(),revision.getParentRevisionId(),revision.getHashVersion(),
                revision.getRevisionSha256(),decision==null?null:decision.getApprovalPolicy(),quiz.isContentLocked(),
                !quiz.isContentLocked() && latest,revision.getQuestionCount(),revision.getOptionCount(),List.copyOf(metadata));
    }
    public boolean contentLocked(Long quizId) {return quizzes.findById(quizId).orElseThrow(this::invalid).isContentLocked();}
    private boolean hasEditedHistory(QuizQuestion row) {
        var visited=new HashSet<Long>();
        while(row!=null) {
            if(!visited.add(row.getQuestionId())) throw invalid();
            if(row.getChangeType()==QuizQuestionChangeType.CREATOR_EDITED) return true;
            row=row.getPreviousQuestionId()==null?null:questions.findById(row.getPreviousQuestionId()).orElseThrow(this::invalid);
        }
        return false;
    }
    private BusinessException invalid(){return new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);}
}
