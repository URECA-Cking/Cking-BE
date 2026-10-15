package kr.co.cking.creator.domain;

/**
 * Creator Space에 올리는 이미지의 종류다(이슈 #590). 프로필 사진과 배너는 같은 저장 경로·같은 정리 작업을 쓰므로,
 * 연결할 때 올린 종류와 같은지 이 값으로 확인한다.
 */
public enum CreatorSpaceImageKind {
    PROFILE, BANNER
}
