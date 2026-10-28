package kr.co.cking.upload.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import kr.co.cking.upload.domain.ImageUploadStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * 결과 큐를 긴 폴링으로 읽어 업로드를 완료·실패로 바꾼다(#780). 서버마다 하나씩 돌며 큐가 메시지를 나눠 준다.
 *
 * <p>반영에 실패한 메시지는 지우지 않아 다시 보이고, 5번 실패하면 결과 DLQ로 간다.
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${cking.image-pipeline.results-queue-url:}'.isEmpty()")
public class ImageUploadResultListener implements SmartLifecycle {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WAIT_SECONDS = 20;
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);

    private final SqsClient sqs;
    private final ImageUploadService service;
    private final String queueUrl;
    private volatile boolean running;
    private Thread worker;

    public ImageUploadResultListener(SqsClient sqs, ImageUploadService service,
                                     @Value("${cking.image-pipeline.results-queue-url}") String queueUrl) {
        this.sqs = sqs;
        this.service = service;
        this.queueUrl = queueUrl;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("image-upload-results").daemon(true).start(this::poll);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                        .maxNumberOfMessages(10).waitTimeSeconds(WAIT_SECONDS)).messages();
                messages.forEach(this::handle);
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("image upload result polling failed", e);
                sleep(ERROR_BACKOFF);
            }
        }
    }

    void handle(Message message) {
        try {
            JsonNode body = JSON.readTree(message.body());
            service.complete(body.path("key").asText(), ImageUploadStatus.valueOf(body.path("status").asText()),
                    body.path("reason").asText(null));
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (Exception e) {
            log.warn("image upload result handling failed: messageId={}", message.messageId(), e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
