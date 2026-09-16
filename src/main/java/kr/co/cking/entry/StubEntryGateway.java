package kr.co.cking.entry;

import org.springframework.stereotype.Component;

/**
 * T2-03(Redis Lua 원자 처리)이 실제 구현을 붙이기 전까지 앱 컨텍스트를 띄우기 위한 임시 스텁.
 * ponytail: 항상 SYSTEM_ERROR만 반환 — 실제 Gate/Lua 연동이 끝나면 이 클래스를 삭제하고 교체한다.
 */
@Component
class StubEntryGateway implements EntryGateway {

    @Override
    public EntryResultCode apply(Long eventId, Long creatorId, EntryRequest request) {
        return EntryResultCode.SYSTEM_ERROR;
    }
}
