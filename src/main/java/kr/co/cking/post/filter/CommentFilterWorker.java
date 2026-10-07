package kr.co.cking.post.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 댓글 하나를 필터 서비스에 판정시키고 결과를 저장한다. Executor 스레드에서 실행되며 어떤 실패도 밖으로 던지지 않는다.
 *
 * <p>필터 서비스 장애는 댓글을 통과 상태로 두고 FAILED로 기록해 재필터링이 다시 처리하게 한다. 이 클래스는 Transaction을
 * 열지 않는다. 읽기와 저장은 {@link CommentFilterResultService}가 각각 짧은 Transaction으로 처리한다.
 *
 * <p>판정을 저장하면 {@code info} 로그를 한 줄 남긴다(이슈 #488). 댓글 원문은 남기지 않고 {@code commentId}로 DB의 댓글과
 * 연결한다. {@code reasons}는 규칙 코드(예: {@code privacy:phone})일 때만 남기고, 규칙 코드 모양이 아니면(공백·구두점이
 * 있거나 긴 값) 필터 응답에 문장이 섞인 것으로 보고 {@value #INVALID_REASON}으로 바꿔 남긴다. 저장하는 사유는 바꾸지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class CommentFilterWorker {

    static final String INVALID_REASON = "invalid";
    /** 규칙 코드: 소문자 유형 하나({@code classifier}) 또는 {@code 유형:이름}(이름은 글자·숫자·밑줄 30자 이하). */
    private static final Pattern REASON_CODE = Pattern.compile("^[a-z][a-z_]{0,19}(:[\\p{L}\\p{N}_]{1,30})?$");

    private final CommentFilterClient client;
    private final CommentFilterResultService resultService;

    public void process(Long commentId) {
        try {
            Optional<CommentFilterResultService.Target> target = resultService.findTarget(commentId);
            if (target.isEmpty()) {
                return;
            }
            String judgedContent = target.get().content();

            CommentFilterResult result;
            long startedAt = System.nanoTime();
            try {
                result = client.moderate(commentId, judgedContent);
            } catch (CommentFilterRejectedException exception) {
                log.error("필터 서비스가 요청을 거절했습니다. 요청 형식을 확인하세요. commentId={}", commentId, exception);
                recordFailure(commentId, judgedContent);
                return;
            } catch (RuntimeException exception) {
                log.warn("필터 서비스 호출에 실패했습니다. 재필터링이 다시 처리합니다. commentId={}", commentId, exception);
                recordFailure(commentId, judgedContent);
                return;
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            boolean saved;
            try {
                saved = resultService.saveResult(commentId, judgedContent, result);
            } catch (IllegalArgumentException exception) {
                log.error("판정 결과를 저장할 수 없습니다. commentId={}", commentId, exception);
                recordFailure(commentId, judgedContent);
                return;
            }
            if (!saved) {
                log.debug("판정 중 댓글이 수정·삭제되어 결과를 버렸습니다. commentId={}", commentId);
                return;
            }
            log.info("댓글 필터 판정을 저장했습니다. commentId={}, action={}, reasons={}, ruleVersion={}, modelVersion={}, elapsedMs={}",
                    commentId, result.action(), loggableReasons(result.reasons()), result.ruleVersion(), result.modelVersion(),
                    elapsedMillis);
        } catch (RuntimeException exception) {
            log.error("댓글 필터 처리 중 예기치 못한 오류가 났습니다. commentId={}", commentId, exception);
        }
    }

    /** 규칙 코드 모양이 아닌 사유는 댓글 내용이 섞였을 수 있어 로그에서 가린다. */
    private static List<String> loggableReasons(List<String> reasons) {
        return reasons.stream()
                .map(reason -> REASON_CODE.matcher(reason).matches() ? reason : INVALID_REASON)
                .toList();
    }

    private void recordFailure(Long commentId, String judgedContent) {
        try {
            resultService.saveFailure(commentId, judgedContent);
        } catch (RuntimeException exception) {
            log.error("판정 실패를 기록하지 못했습니다. commentId={}", commentId, exception);
        }
    }
}
