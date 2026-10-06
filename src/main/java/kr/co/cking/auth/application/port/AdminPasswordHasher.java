package kr.co.cking.auth.application.port;

/** 관리자 비밀번호 원문을 저장 가능한 단방향 해시로 바꾸는 경계다. */
public interface AdminPasswordHasher {

    /** 원문 비밀번호를 검증 가능한 단방향 해시로 변환한다. */
    String hash(String rawPassword);
}
