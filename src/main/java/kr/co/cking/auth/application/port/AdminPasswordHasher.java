package kr.co.cking.auth.application.port;

/** 관리자 비밀번호 원문을 저장 가능한 단방향 해시로 바꾸는 경계다. */
public interface AdminPasswordHasher {

    /** 원문 비밀번호를 검증 가능한 단방향 해시로 변환한다. */
    String hash(String rawPassword);

    /** 입력한 원문 비밀번호가 저장된 해시와 일치하는지 검증한다. */
    boolean matches(String rawPassword, String passwordHash);
}
