package kr.co.cking.snapshot.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
import org.junit.jupiter.api.Test;

class SnapshotHashV2GeneratorTest {
    private final SnapshotHashV2Generator generator = new SnapshotHashV2Generator();

    @Test
    void 상품_구성_가중치_수량을_Snapshot_Hash에_포함한다() {
        SnapshotHashV2Input original = input(List.of(
                new PrizeValue("FIRST", "1등", 1, 5, 1),
                new PrizeValue("SECOND", "2등", 2, 95, 9)));
        SnapshotHashV2Input changedWeight = input(List.of(
                new PrizeValue("FIRST", "1등", 1, 6, 1),
                new PrizeValue("SECOND", "2등", 2, 95, 9)));
        SnapshotHashV2Input changedQuantity = input(List.of(
                new PrizeValue("FIRST", "1등", 1, 5, 2),
                new PrizeValue("SECOND", "2등", 2, 95, 9)));

        assertThat(generator.generate(original).value())
                .isNotEqualTo(generator.generate(changedWeight).value())
                .isNotEqualTo(generator.generate(changedQuantity).value());
        assertThat(generator.canonicalize(original)).startsWith("CKING_SNAPSHOT_V2\n")
                .contains("prizeAlgorithmVersion=PRIZE_WEIGHTED_V1\n", "prizes\n");
    }

    @Test
    void 상품_입력_순서가_달라도_Hash는_같다() {
        PrizeValue first = new PrizeValue("FIRST", "1등", 1, 5, 1);
        PrizeValue second = new PrizeValue("SECOND", "2등", 2, 95, 9);

        assertThat(generator.generate(input(List.of(first, second))).value())
                .isEqualTo(generator.generate(input(List.of(second, first))).value());
    }

    private SnapshotHashV2Input input(List<PrizeValue> prizes) {
        return new SnapshotHashV2Input(10L, 2, "WEIGHTED", "WEIGHTED_V1", "PRIZE_WEIGHTED_V1",
                List.of(new CandidateValue(1L, 3), new CandidateValue(2L, 7)), prizes);
    }
}
