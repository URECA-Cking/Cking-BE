package probe.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * RewardNotificationDatabaseProbeTest가 Hibernate SessionFactory에 직접 등록하는 실험용 매핑이다.
 * Spring Boot의 엔티티 스캔 범위(kr.co.cking) 밖에 두어 다른 Spring 테스트 컨텍스트의 스키마 검증에 포함되지 않게 한다.
 */
public final class RewardNotificationMarkerProbes {

    private RewardNotificationMarkerProbes() {
    }

    @Entity(name="CreatorMarkerProbe") @Table(name="ticket_ledger")
    public static class CreatorMarkerProbe {
        @Id @Column(name="ledger_id") public Long id;
        @Column(name="reward_notification_eligible", nullable=false, insertable=false, updatable=false) public boolean eligible;
        public CreatorMarkerProbe() { }
    }

    @Entity(name="CommonMarkerProbe") @Table(name="common_ticket_ledger")
    public static class CommonMarkerProbe {
        @Id @Column(name="ledger_id") public Long id;
        @Column(name="reward_notification_eligible", nullable=false, insertable=false, updatable=false) public boolean eligible;
        public CommonMarkerProbe() { }
    }
}
