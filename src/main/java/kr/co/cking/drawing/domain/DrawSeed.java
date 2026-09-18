package kr.co.cking.drawing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** DB 바이너리 Seed와 도메인 {@link DrawingSeed} 값 객체 사이의 영속화 경계. */
@Getter
@Entity
@Table(name = "draw_seed")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seed_value", nullable = false, updatable = false, length = 255)
    private byte[] seedValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static DrawSeed create(DrawingSeed seed) {
        if (seed == null) {
            throw new IllegalArgumentException("DrawingSeed는 필수입니다.");
        }

        DrawSeed drawSeed = new DrawSeed();
        // 외부 배열 변경으로 영속화 값이 훼손되지 않도록 방어적 복사 수행.
        drawSeed.seedValue = seed.bytes();
        return drawSeed;
    }

    public DrawingSeed restore() {
        try {
            // DB에서 읽은 값의 길이와 정규 형식을 추첨 실행 전에 재검증.
            return DrawingSeed.fromBytes(seedValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 Drawing Seed가 올바르지 않습니다.", exception);
        }
    }

    /** 테스트와 진단 시에도 내부 배열 노출을 방지하기 위한 복사본 반환. */
    public byte[] getSeedValue() {
        return seedValue == null ? null : Arrays.copyOf(seedValue, seedValue.length);
    }
}
