package kr.co.cking.quiz.domain;

import java.util.Set;

/**
 * 평가 Job(attempt) 실행 상태(이슈 #529). PENDING → RUNNING·FAILED, RUNNING → SUCCEEDED·FAILED·UNKNOWN.
 * SUCCEEDED는 유효한 평가 결과를 저장했다는 뜻이며 판정이 pass라는 뜻은 아니다.
 */
public enum QuizJudgeJobStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    public static final Set<QuizJudgeJobStatus> ACTIVE = Set.of(PENDING, RUNNING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
