package kr.co.cking.common.image;

/**
 * 입력 이미지가 공통 규칙이나 도메인 정책을 위반했음을 나타낸다.
 *
 * <p>업무 오류 코드는 도메인마다 다르므로, 호출한 도메인이 자기 ErrorCode로 변환한다.
 */
public class InvalidImageException extends RuntimeException {

    public InvalidImageException() {
        super("이미지가 유효하지 않습니다.");
    }
}
