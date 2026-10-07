package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class CommentFilterWorkerTest {

    private static final Long COMMENT_ID = 500L;
    private static final String CONTENT = "이 댓글의 원문은 로그에 남으면 안 된다";
    private static final CommentFilterResult BLOCK = new CommentFilterResult(
            CommentFilterAction.BLOCK, List.of("privacy:phone", "spam:link"), "local-1", "threat-ctx-v1-reviewed");
    private static final CommentFilterResult PASS = new CommentFilterResult(
            CommentFilterAction.PASS, List.of(), "local-1", "threat-ctx-v1-reviewed");

    private final CommentFilterClient client = mock(CommentFilterClient.class);
    private final CommentFilterResultService resultService = mock(CommentFilterResultService.class);
    private final CommentFilterWorker worker = new CommentFilterWorker(client, resultService);

    @Test
    void 판정을_요청한_본문으로_필터를_호출하고_같은_본문으로_결과를_저장한다() {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willReturn(BLOCK);
        given(resultService.saveResult(COMMENT_ID, CONTENT, BLOCK)).willReturn(true);

        worker.process(COMMENT_ID);

        verify(client).moderate(COMMENT_ID, CONTENT);
        verify(resultService).saveResult(COMMENT_ID, CONTENT, BLOCK);
        verify(resultService, never()).saveFailure(anyLong(), anyString());
    }

    @Test
    void 판정을_저장하면_댓글_ID와_결과를_info로_남기고_원문은_남기지_않는다(CapturedOutput output) {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willReturn(BLOCK);
        given(resultService.saveResult(COMMENT_ID, CONTENT, BLOCK)).willReturn(true);

        worker.process(COMMENT_ID);

        assertThat(output.getAll())
                .contains("댓글 필터 판정을 저장했습니다.")
                .contains("commentId=500")
                .contains("action=BLOCK")
                .contains("reasons=[privacy:phone, spam:link]")
                .contains("ruleVersion=local-1")
                .contains("modelVersion=threat-ctx-v1-reviewed")
                .contains("elapsedMs=")
                .doesNotContain(CONTENT);
    }

    @Test
    void 규칙_코드_모양이_아닌_사유는_로그에서_invalid로_가리고_저장하는_사유는_바꾸지_않는다(CapturedOutput output) {
        String sentenceLeak = "이 댓글의 원문은 로그에 남으면 안 된다";
        CommentFilterResult leaking = new CommentFilterResult(CommentFilterAction.BLOCK,
                List.of("profanity:병신", "classifier", sentenceLeak, "spam:link http://x.y", "PRIVACY:phone",
                        "spam:" + "a".repeat(31), "privacy:"), "local-1", "model-1");
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willReturn(leaking);
        given(resultService.saveResult(COMMENT_ID, CONTENT, leaking)).willReturn(true);

        worker.process(COMMENT_ID);

        assertThat(output.getAll())
                .contains("reasons=[profanity:병신, classifier, invalid, invalid, invalid, invalid, invalid]")
                .doesNotContain(sentenceLeak)
                .doesNotContain("http://x.y");
        verify(resultService).saveResult(COMMENT_ID, CONTENT, leaking);
    }

    @Test
    void PASS_판정도_로그로_남긴다(CapturedOutput output) {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willReturn(PASS);
        given(resultService.saveResult(COMMENT_ID, CONTENT, PASS)).willReturn(true);

        worker.process(COMMENT_ID);

        assertThat(output.getAll()).contains("action=PASS").contains("reasons=[]");
    }

    @Test
    void 이미_판정을_마쳤거나_삭제된_댓글은_필터를_호출하지_않는다() {
        given(resultService.findTarget(COMMENT_ID)).willReturn(Optional.empty());

        worker.process(COMMENT_ID);

        verify(client, never()).moderate(anyLong(), anyString());
        verify(resultService, never()).saveResult(anyLong(), anyString(), any());
        verify(resultService, never()).saveFailure(anyLong(), anyString());
    }

    @Test
    void 필터가_요청을_거절하면_실패로_기록하고_error로_남긴다(CapturedOutput output) {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT))
                .willThrow(new CommentFilterRejectedException("필터 서비스가 요청을 거절했습니다. status=400", null));

        worker.process(COMMENT_ID);

        verify(resultService).saveFailure(COMMENT_ID, CONTENT);
        verify(resultService, never()).saveResult(anyLong(), anyString(), any());
        assertThat(output.getAll()).contains("필터 서비스가 요청을 거절했습니다").doesNotContain("판정을 저장했습니다");
    }

    @Test
    void 필터_장애는_실패로_기록하고_판정_로그는_남기지_않는다(CapturedOutput output) {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willThrow(new CommentFilterException("필터 서비스 호출에 실패했습니다."));

        worker.process(COMMENT_ID);

        verify(resultService).saveFailure(COMMENT_ID, CONTENT);
        assertThat(output.getAll()).contains("필터 서비스 호출에 실패했습니다").doesNotContain("판정을 저장했습니다");
    }

    @Test
    void 판정_결과를_저장할_수_없으면_실패로_기록한다() {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willReturn(BLOCK);
        given(resultService.saveResult(COMMENT_ID, CONTENT, BLOCK))
                .willThrow(new IllegalArgumentException("판정 사유가 500자를 넘습니다."));

        worker.process(COMMENT_ID);

        verify(resultService).saveFailure(COMMENT_ID, CONTENT);
    }

    @Test
    void 판정_중_수정되거나_삭제되어_결과가_버려지면_실패를_기록하지도_판정_로그를_남기지도_않는다(CapturedOutput output) {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willReturn(BLOCK);
        given(resultService.saveResult(COMMENT_ID, CONTENT, BLOCK)).willReturn(false);

        worker.process(COMMENT_ID);

        verify(resultService, never()).saveFailure(anyLong(), anyString());
        assertThat(output.getAll()).doesNotContain("판정을 저장했습니다");
    }

    @Test
    void 실패를_기록하지_못해도_예외를_밖으로_던지지_않는다() {
        givenTarget(CONTENT);
        given(client.moderate(COMMENT_ID, CONTENT)).willThrow(new CommentFilterException("장애"));
        willThrow(new IllegalStateException("db down")).given(resultService).saveFailure(COMMENT_ID, CONTENT);

        assertThatCode(() -> worker.process(COMMENT_ID)).doesNotThrowAnyException();
    }

    @Test
    void 판정_대상을_읽다가_예외가_나도_밖으로_던지지_않는다() {
        given(resultService.findTarget(COMMENT_ID)).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> worker.process(COMMENT_ID)).doesNotThrowAnyException();
        verify(client, never()).moderate(anyLong(), anyString());
    }

    private void givenTarget(String content) {
        given(resultService.findTarget(COMMENT_ID))
                .willReturn(Optional.of(new CommentFilterResultService.Target(content)));
    }
}
