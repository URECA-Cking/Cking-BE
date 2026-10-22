package kr.co.cking.drawing.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** REDRAW Drawing의 Hash 입력에 포함된 제외 Member와 당시 사유를 보존한다. */
@Getter
@Entity
@Table(name = "redraw_exclusion")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RedrawExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "drawing_id", nullable = false, updatable = false)
    private Long drawingId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "exclusion_reason", nullable = false, updatable = false, length = 30)
    private RedrawExclusionReason exclusionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 확정된 REDRAW Drawing 입력의 제외 대상 한 명을 만든다. */
    public static RedrawExclusion of(Long drawingId, Long memberId, RedrawExclusionReason exclusionReason) {
        requirePositive(drawingId, "drawingId");
        requirePositive(memberId, "memberId");
        if (exclusionReason == null) {
            throw new IllegalArgumentException("exclusionReason은 필수입니다.");
        }
        RedrawExclusion exclusion = new RedrawExclusion();
        exclusion.drawingId = drawingId;
        exclusion.memberId = memberId;
        exclusion.exclusionReason = exclusionReason;
        return exclusion;
    }
}
