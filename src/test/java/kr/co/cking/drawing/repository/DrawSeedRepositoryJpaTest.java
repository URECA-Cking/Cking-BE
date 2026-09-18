package kr.co.cking.drawing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

import jakarta.persistence.EntityManager;
import kr.co.cking.drawing.domain.DrawSeed;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DrawSeedRepositoryJpaTest {

    private static final DrawingSeed SEED = DrawingSeed.from("ab".repeat(32));

    @Autowired
    private DrawSeedRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void Seed를_32바이트로_저장하고_ID로_복원한다() {
        DrawSeed saved = repository.saveAndFlush(DrawSeed.create(SEED));
        entityManager.clear();

        DrawSeed found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getSeedValue()).hasSize(DrawingSeed.BYTE_LENGTH);
        assertThat(found.restore()).isEqualTo(SEED);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 잘못된_길이의_DB_Seed는_복원_단계에서_차단한다() {
        byte[] malformedSeed = new byte[31];
        entityManager.createNativeQuery("INSERT INTO draw_seed (seed_value) VALUES (:seedValue)")
                .setParameter("seedValue", malformedSeed)
                .executeUpdate();
        entityManager.flush();
        Long malformedSeedId = ((Number) entityManager.createNativeQuery("""
                        SELECT id
                        FROM draw_seed
                        WHERE OCTET_LENGTH(seed_value) = 31
                        ORDER BY id DESC
                        LIMIT 1
                        """)
                .getSingleResult()).longValue();
        entityManager.clear();

        DrawSeed found = repository.findById(malformedSeedId).orElseThrow();

        assertThatThrownBy(found::restore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("올바르지 않습니다");
    }
}
