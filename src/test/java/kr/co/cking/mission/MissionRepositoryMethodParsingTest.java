package kr.co.cking.mission;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.parser.PartTree;

import static org.assertj.core.api.Assertions.assertThatCode;

class MissionRepositoryMethodParsingTest {

    @Test
    void creator와_완료이력_파생조회가_실제_모델_프로퍼티로_파싱된다() {
        assertThatCode(() -> new PartTree("findByCreatorIdAndTypeIn", Mission.class))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PartTree(
                "findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey", MissionCompletion.class))
                .doesNotThrowAnyException();
    }
}
