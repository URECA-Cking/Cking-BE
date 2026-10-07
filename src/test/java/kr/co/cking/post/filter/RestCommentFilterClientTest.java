package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterAction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

class RestCommentFilterClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://filter");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestCommentFilterClient client = new RestCommentFilterClient(builder.build());

    @Test
    void 사유가_있는_BLOCK_응답을_판정으로_해석한다() {
        respond("""
                {"action":"BLOCK","reasons":["privacy:phone","spam:link"],"ruleVersion":"r1","modelVersion":"m1"}""");

        CommentFilterResult result = client.moderate(1L, "본문");

        assertThat(result.action()).isEqualTo(CommentFilterAction.BLOCK);
        assertThat(result.reasons()).containsExactly("privacy:phone", "spam:link");
        assertThat(result.ruleVersion()).isEqualTo("r1");
    }

    @Test
    void PASS는_사유가_비어_있어도_받아들인다() {
        respond("""
                {"action":"PASS","reasons":[],"ruleVersion":"r1","modelVersion":"m1"}""");

        assertThat(client.moderate(1L, "본문").action()).isEqualTo(CommentFilterAction.PASS);
    }

    @Test
    void reasons가_누락된_BLOCK_응답은_빈_사유로_바꾸지_않고_잘못된_응답으로_처리한다() {
        respond("""
                {"action":"BLOCK","ruleVersion":"r1","modelVersion":"m1"}""");

        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterException.class);
    }

    @Test
    void reasons가_null이면_잘못된_응답이다() {
        respond("""
                {"action":"BLOCK","reasons":null,"ruleVersion":"r1","modelVersion":"m1"}""");

        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterException.class);
    }

    @Test
    void reasons가_누락된_PASS_응답도_잘못된_응답이다() {
        respond("""
                {"action":"PASS","ruleVersion":"r1","modelVersion":"m1"}""");

        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterException.class);
    }

    @Test
    void 사유가_빈_BLOCK_응답은_잘못된_응답이다() {
        respond("""
                {"action":"BLOCK","reasons":[],"ruleVersion":"r1","modelVersion":"m1"}""");

        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterException.class);
    }

    @Test
    void 규칙과_모델_버전이_없거나_알_수_없는_action이면_잘못된_응답이다() {
        respond("""
                {"action":"BLOCK","reasons":["spam:link"],"modelVersion":"m1"}""");
        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterException.class);

        respond("""
                {"action":"HOLD","reasons":["x"],"ruleVersion":"r1","modelVersion":"m1"}""");
        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterException.class);
    }

    @Test
    void 필터_서비스의_4xx는_거절_5xx는_장애로_구분한다() {
        server.expect(requestTo("http://filter/moderate")).andRespond(withStatus(BAD_REQUEST));
        assertThatThrownBy(() -> client.moderate(1L, "본문")).isInstanceOf(CommentFilterRejectedException.class);

        server.reset();
        server.expect(requestTo("http://filter/moderate")).andRespond(withStatus(SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.moderate(1L, "본문"))
                .isInstanceOf(CommentFilterException.class)
                .isNotInstanceOf(CommentFilterRejectedException.class);
    }

    private void respond(String json) {
        server.reset();
        server.expect(requestTo("http://filter/moderate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }
}
