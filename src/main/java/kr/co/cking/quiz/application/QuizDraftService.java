package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationValidator;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 검증된 AI 생성 결과를 새 Quiz와 첫 revision으로 저장한다(이슈 #521). 외부 API가 없는 내부 Service이며,
 * 생성 작업 Issue에서 호출한다.
 *
 * <p>모든 검증을 INSERT 전에 마치고 한 Transaction으로 저장하므로, 실패하면 어떤 행도 남지 않는다. 기존
 * {@link QuizGenerationValidator}의 예외({@code QuizInputException}, {@code QuizGenerationValidationException})는
 * 그대로 전파하고, 저장 길이 상한 위반은 {@code QUIZ_DRAFT_INVALID}다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class QuizDraftService {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final CreatorRepository creatorRepository;
    private final QuizRepository quizRepository;
    private final QuizRevisionRepository revisionRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizOptionRepository optionRepository;
    private final QuizGenerationValidator generationValidator;
    private final Clock clock;

    public QuizDraft create(QuizDraftCommand command) {
        validate(command);
        GeneratedQuiz generated = command.generated();
        List<QuizQuestionContent> questions = toContents(generated.questions());
        Instant now = clock.instant();

        Quiz quiz = quizRepository.save(
                new Quiz(command.creatorId(), command.sourceVideoId(), command.input().contentText(), now));
        String revisionSha256 = QuizRevisionReader.revisionSha256(
                quiz.getContentTextSha256(), command.title(), generated.promptVersion(), questions);
        QuizRevision revision = revisionRepository.save(new QuizRevision(
                quiz.getQuizId(), 1, command.title(), generated.promptVersion(), questions.size(),
                command.input().optionCount(), revisionSha256, now));
        for (QuizQuestionContent content : questions) {
            QuizQuestion question = questionRepository.save(new QuizQuestion(
                    revision.getRevisionId(), content.questionNo(), content.question(),
                    content.correctOptionIndex(), content.explanation(), content.sourceEvidence()));
            for (int optionNo = 0; optionNo < content.options().size(); optionNo++) {
                optionRepository.save(new QuizOption(question.getQuestionId(), optionNo, content.options().get(optionNo)));
            }
        }
        return new QuizDraft(quiz.getQuizId(), revision.getRevisionId());
    }

    private void validate(QuizDraftCommand command) {
        if (command == null || command.creatorId() == null || command.input() == null) {
            throw invalid("저장 명령이 비어 있습니다.");
        }
        generationValidator.validate(command.input(), command.generated());
        if (!creatorRepository.existsById(command.creatorId())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (command.sourceVideoId() == null || !VIDEO_ID.matcher(command.sourceVideoId()).matches()) {
            throw invalid("YouTube videoId 형식이 아닙니다.");
        }
        requireText(command.title(), QuizRevision.MAX_TITLE_LENGTH, "title");
        requireText(command.generated().promptVersion(), QuizRevision.MAX_PROMPT_VERSION_LENGTH, "promptVersion");
        for (GeneratedQuestion question : command.generated().questions()) {
            requireText(question.question(), QuizQuestion.MAX_QUESTION_LENGTH, "question");
            requireText(question.explanation(), QuizQuestion.MAX_EXPLANATION_LENGTH, "explanation");
            requireText(question.sourceEvidence(), QuizQuestion.MAX_SOURCE_EVIDENCE_LENGTH, "sourceEvidence");
            for (String option : question.options()) {
                requireText(option, QuizOption.MAX_OPTION_LENGTH, "option");
            }
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + "는 비어 있지 않은 " + maxLength + "자 이하 문자열이어야 합니다.");
        }
    }

    private List<QuizQuestionContent> toContents(List<GeneratedQuestion> generated) {
        List<QuizQuestionContent> contents = new ArrayList<>();
        for (int questionNo = 0; questionNo < generated.size(); questionNo++) {
            GeneratedQuestion question = generated.get(questionNo);
            contents.add(new QuizQuestionContent(questionNo, question.question(), List.copyOf(question.options()),
                    question.correctOptionIndex(), question.explanation(), question.sourceEvidence()));
        }
        return contents;
    }

    private BusinessException invalid(String detail) {
        return new BusinessException(QuizErrorCode.QUIZ_DRAFT_INVALID, detail);
    }
}
