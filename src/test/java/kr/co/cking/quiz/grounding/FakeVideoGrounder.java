package kr.co.cking.quiz.grounding;

import kr.co.cking.quiz.QuizGenerationFixtures;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트 전용 영상 Grounding. 실제 Provider 없이 고정한 원문과 facts를 돌려준다. {@link #hold()}로 응답을 붙잡아 진행
 * 중인 Grounding 단계를 만들 수 있다.
 */
public class FakeVideoGrounder implements VideoGrounder {

    public static final String MODEL = "fake-grounding";
    public static final List<GroundingFact> FACTS = List.of(
            new GroundingFact("물은 증발해 구름이 된다.", EvidenceType.SPEECH, "물은 증발해 구름이 됩니다", "00:01", "00:03"),
            new GroundingFact("구름은 비가 된다.", EvidenceType.VISUAL, "비 내리는 장면", null, null));

    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch gate = new CountDownLatch(0);

    @Override
    public VideoGrounding ground(String videoId) {
        calls.incrementAndGet();
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Grounding 대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return new VideoGrounding(QuizGenerationFixtures.CONTENT_TEXT, FACTS, GroundingPrompt.VERSION, MODEL);
    }

    public void hold() {
        gate = new CountDownLatch(1);
    }

    public void release() {
        gate.countDown();
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        release();
        calls.set(0);
    }
}
