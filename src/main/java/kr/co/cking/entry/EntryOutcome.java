package kr.co.cking.entry;

/** 컨트롤러가 공통 응답 봉투의 {@code code}에 실어 보낼 원본 결과코드와 페이로드. */
public record EntryOutcome(EntryResultCode code, EntryResponse response) {
}
