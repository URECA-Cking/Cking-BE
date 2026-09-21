package kr.co.cking.redraw.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 재추첨 요청이 생성 시점에 점유한 Winner 결원을 보관한다. */
@Getter
@Entity
@Table(name = "redraw_request_vacancy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RedrawRequestVacancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "redraw_request_id", nullable = false, updatable = false)
    private Long redrawRequestId;

    @Column(name = "winner_id", nullable = false, updatable = false)
    private Long winnerId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 특정 RedrawRequest가 재추첨 대상으로 확정한 Winner 결원을 연결한다. */
    public static RedrawRequestVacancy of(Long redrawRequestId, Long winnerId) {
        requirePositive(redrawRequestId, "redrawRequestId");
        requirePositive(winnerId, "winnerId");

        RedrawRequestVacancy vacancy = new RedrawRequestVacancy();
        vacancy.redrawRequestId = redrawRequestId;
        vacancy.winnerId = winnerId;
        return vacancy;
    }
}
