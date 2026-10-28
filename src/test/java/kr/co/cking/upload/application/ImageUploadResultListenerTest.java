package kr.co.cking.upload.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;

import java.util.function.Consumer;
import kr.co.cking.upload.domain.ImageUploadStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

class ImageUploadResultListenerTest {

    private final SqsClient sqs = mock(SqsClient.class);
    private final ImageUploadService service = mock(ImageUploadService.class);
    private final ImageUploadResultListener listener = new ImageUploadResultListener(sqs, service, "queue");

    @Test
    @SuppressWarnings("unchecked")
    void 결과를_반영한_메시지만_지운다() {
        listener.handle(message("{\"key\":\"post-images/1/a.jpg\",\"status\":\"FAILED\",\"reason\":\"decode\"}"));

        then(service).should().complete("post-images/1/a.jpg", ImageUploadStatus.FAILED, "decode");
        then(sqs).should().deleteMessage(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 반영에_실패하면_지우지_않아_다시_받고_다섯_번_실패하면_DLQ로_간다() {
        doThrow(new IllegalStateException("db down")).when(service).complete(any(), any(), any());

        listener.handle(message("{\"key\":\"post-images/1/a.jpg\",\"status\":\"READY\"}"));
        listener.handle(message("not json"));

        then(sqs).should(never()).deleteMessage(any(Consumer.class));
        then(sqs).should(never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    private static Message message(String body) {
        return Message.builder().messageId("m").receiptHandle("r").body(body).build();
    }
}
