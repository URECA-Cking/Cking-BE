package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterAction;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * 별도 서비스로 띄운 필터에 {@code POST /moderate}로 판정을 요청한다.
 *
 * <p>요청 {@code {commentId, content}}, 응답 {@code {action, reasons, ruleVersion, modelVersion}}. 이 형식은 필터 서비스와
 * 아직 확정하지 않은 계약이므로 바뀌면 이 클래스의 내부 DTO만 고친다.
 *
 * <p>{@code reasons}가 없거나 null이면 잘못된 응답이다. 빈 배열로 바꿔 받으면 개인정보(privacy:*) 사유가 지워질 수
 * 있다. BLOCK은 사유가 1개 이상이어야 하고({@link CommentFilterResult}), 잘못된 응답은 {@link CommentFilterException}으로
 * 처리되어 댓글의 이전 판정을 유지한 채 FAILED로 기록된다.
 */
public class RestCommentFilterClient implements CommentFilterClient {

    private final RestClient restClient;

    public RestCommentFilterClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CommentFilterResult moderate(Long commentId, String content) {
        Response response;
        try {
            response = restClient.post()
                    .uri("/moderate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(commentId, content))
                    .retrieve()
                    .body(Response.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (exception.getStatusCode().is4xxClientError()) {
                throw new CommentFilterRejectedException("필터 서비스가 요청을 거절했습니다. status=" + status, exception);
            }
            throw new CommentFilterException("필터 서비스가 오류를 응답했습니다. status=" + status, exception);
        } catch (RestClientException exception) {
            throw new CommentFilterException("필터 서비스 호출에 실패했습니다.", exception);
        }
        if (response == null) {
            throw new CommentFilterException("필터 서비스 응답 본문이 비어 있습니다.");
        }
        try {
            return response.toResult();
        } catch (RuntimeException exception) {
            throw new CommentFilterException("필터 서비스 응답을 해석할 수 없습니다.", exception);
        }
    }

    private record Request(Long commentId, String content) {
    }

    private record Response(String action, List<String> reasons, String ruleVersion, String modelVersion) {

        CommentFilterResult toResult() {
            return new CommentFilterResult(
                    CommentFilterAction.valueOf(action), reasons, ruleVersion, modelVersion);
        }
    }
}
