package kr.co.cking.post.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 댓글 하나를 필터 서비스에 판정시키고 결과를 저장한다. Executor 스레드에서 실행되며 어떤 실패도 밖으로 던지지 않는다.
 *
 * <p>필터 서비스 장애는 댓글을 통과 상태로 두고 FAILED로 기록해 재필터링이 다시 처리하게 한다. 이 클래스는 Transaction을
 * 열지 않는다. 읽기와 저장은 {@link CommentFilterResultService}가 각각 짧은 Transaction으로 처리한다.
 *
 * <p>판정을 저장하면 {@code info} 로그를 한 줄 남긴다(이슈 #488). 댓글 원문은 남기지 않고 {@code commentId}로 DB의 댓글과
 * 연결한다. 필터 서비스 응답은 외부 입력이라 그대로 기록하지 않는다.
 * <ul>
 *   <li>{@code reasons}: 유형이 {@link #REASON_TYPES}에 있을 때만 남기고, 아니면 {@value #INVALID_VALUE}다. 이름
 *       ({@code 유형:이름})은 영문 소문자와 밑줄뿐일 때만 남기고 아니면 유형만 남긴다. 숫자·한글·공백이 든 이름에는 전화번호나
 *       댓글 문장이 들어올 수 있어서다.</li>
 *   <li>{@code ruleVersion}·{@code modelVersion}: ASCII 영숫자와 {@code . _ -}뿐인 50자 이하일 때만 남기고 아니면
 *       {@value #INVALID_VALUE}다.</li>
 * </ul>
 * 저장하는 값은 바꾸지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class CommentFilterWorker {

    static final String INVALID_VALUE = "invalid";
    /** 로그에 남겨도 되는 사유 유형. 필터 서비스의 규칙 코드 유형이다. */
    private static final Set<String> REASON_TYPES = Set.of("classifier", "model", "profanity", "privacy", "spam");
    /** 사유 이름. 영문 소문자와 밑줄뿐이라 숫자·한글·공백이 든 개인정보나 댓글 문장은 걸러진다. */
    private static final Pattern REASON_NAME = Pattern.compile("^[a-z_]{1,30}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$");

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
                    commentId, result.action(), loggableReasons(result.reasons()), loggableVersion(result.ruleVersion()),
                    loggableVersion(result.modelVersion()), elapsedMillis);
        } catch (RuntimeException exception) {
            log.error("댓글 필터 처리 중 예기치 못한 오류가 났습니다. commentId={}", commentId, exception);
        }
    }

    private static List<String> loggableReasons(List<String> reasons) {
        return reasons.stream().map(CommentFilterWorker::loggableReason).toList();
    }

    private static String loggableReason(String reason) {
        int colon = reason.indexOf(':');
        String type = colon < 0 ? reason : reason.substring(0, colon);
        if (!REASON_TYPES.contains(type)) {
            return INVALID_VALUE;
        }
        if (colon < 0) {
            return type;
        }
        String name = reason.substring(colon + 1);
        return REASON_NAME.matcher(name).matches() ? type + ":" + name : type;
    }

    private static String loggableVersion(String version) {
        return VERSION.matcher(version).matches() ? version : INVALID_VALUE;
    }

    private void recordFailure(Long commentId, String judgedContent) {
        try {
            resultService.saveFailure(commentId, judgedContent);
        } catch (RuntimeException exception) {
            log.error("판정 실패를 기록하지 못했습니다. commentId={}", commentId, exception);
        }
    }
}
