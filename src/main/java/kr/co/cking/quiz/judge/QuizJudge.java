package kr.co.cking.quiz.judge;

import java.time.Duration;

/**
 * Quiz revision을 contentText 기준으로 평가하는 Pointwise Judge(이슈 #529). 영상을 검증하거나 Creator를 대신해
 * 승인하지 않는다. 한 번 호출이 평가 한 번(attempt)이며 DB Transaction 밖에서 호출한다.
 *
 * <p>호출자(평가 Worker)는 결과를 아래처럼 분류한다. 어느 경우에도 자동으로 다시 호출하지 않는다.
 * <ul>
 *   <li>반환값: 평가 실행 성공. fail·uncertain 판정도 여기에 포함된다</li>
 *   <li>{@link QuizJudgeTimeoutException}: 결과를 확정할 수 없음(UNKNOWN). 응답 대기 초과와 전체 실행 시간 초과를
 *       {@link QuizJudgeTimeoutException#isExecutionDeadlineExceeded()}로 구분한다</li>
 *   <li>{@link QuizJudgeInterruptedException}: 실행 중단으로 결과를 확정할 수 없음(UNKNOWN). 시간 초과가 아니다</li>
 *   <li>{@link QuizJudgeException}: 결과가 확정된 실패(FAILED). 사유는 {@link QuizJudgeException.Failure}다</li>
 * </ul>
 * 세 예외 모두 그때까지 보낸 HTTP 요청과 관측한 사용량을 담는다.
 */
public interface QuizJudge {

    /**
     * @throws QuizJudgeTimeoutException     결과를 확인할 수 없는 시간 초과
     * @throws QuizJudgeInterruptedException 실행 중단
     * @throws QuizJudgeException            그 밖의 확정된 실패
     */
    QuizJudgeResult judge(QuizJudgeInput input);

    /** 지금 실행하면 쓸 설정 snapshot. Provider를 호출하지 않는다. 한도를 정하지 않은 문항 수면 예외다. */
    QuizJudgeSettings currentSettings(int questionCount);

    /** 호출에 필요한 설정(API key 등)이 갖춰졌는지. Provider를 호출하지 않는다. 새 평가를 접수하기 전에 확인한다. */
    boolean isConfigured();

    /**
     * {@link #judge} 한 번의 전체 실행 상한. 구현은 호출 시작부터 이 시간을 넘긴 결과를 돌려주지 않는다. 호출자는 호출
     * 직전부터 같은 상한을 재어, 반환 뒤 저장 전까지 이 시간을 넘겼으면 결과를 쓰지 않는다.
     */
    Duration executionTimeout();
}
