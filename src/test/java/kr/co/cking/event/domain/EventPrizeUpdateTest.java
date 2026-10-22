package kr.co.cking.event.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPrizeUpdateTest {
    private static final Instant START = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    static Stream<PrizeConfig> changedGrand() {
        return Stream.of(
                new PrizeConfig("GRAND", "대상", 1, 10, 5),
                new PrizeConfig("GRAND", "대상", 1, 20, 2),
                new PrizeConfig("GRAND", "변경한 이름", 2, 10, 2),
                new PrizeConfig("grand", "대상", 1, 10, 2));
    }

    @ParameterizedTest
    @MethodSource("changedGrand")
    void 같은_키의_상품은_Entity와_연관관계를_유지하며_내용을_갱신한다(PrizeConfig changed) {
        Event event = event(List.of(grand()));
        EventPrize original = event.getPrizes().getFirst();

        update(event, List.of(changed));

        assertThat(event.getPrizes()).hasSize(1);
        assertThat(event.getPrizes().getFirst()).isSameAs(original);
        assertThat(original.getEvent()).isSameAs(event);
        assertThat(original.toConfig()).isEqualTo(changed);
    }

    @Test
    void 유지_삭제_추가를_섞어도_유지한_상품은_재생성하지_않는다() {
        Event event = event(List.of(grand(), new PrizeConfig("OLD", "이전", 2, 1, 1)));
        EventPrize original = event.getPrizes().getFirst();
        PrizeConfig added = new PrizeConfig("NEW", "신규", 2, 3, 1);

        update(event, List.of(added, grand()));

        assertThat(event.getPrizes()).extracting(EventPrize::toConfig).containsExactly(grand(), added);
        assertThat(event.getPrizes().getFirst()).isSameAs(original);
    }

    @Test
    void 같은_요청을_반복해도_유지한_Entity가_바뀌지_않는다() {
        Event event = event(List.of(grand()));
        EventPrize original = event.getPrizes().getFirst();
        update(event, List.of(grand()));
        update(event, List.of(grand()));
        assertThat(event.getPrizes().getFirst()).isSameAs(original);
    }

    @Test
    void 우선순위_교환은_키와_Entity를_유지하고_조회_순서만_바꾼다() {
        PrizeConfig second = new PrizeConfig("SECOND", "차상", 2, 1, 1);
        Event event = event(List.of(grand(), second));
        EventPrize originalGrand = event.getPrizes().getFirst();
        EventPrize originalSecond = event.getPrizes().getLast();

        update(event, List.of(new PrizeConfig("GRAND", "대상", 2, 10, 2),
                new PrizeConfig("SECOND", "차상", 1, 1, 1)));

        assertThat(event.getPrizes()).containsExactly(originalSecond, originalGrand);
        assertThat(event.getPrizeConfigs()).extracting(PrizeConfig::priority).containsExactly(1, 2);
    }

    @Test
    void 중복_키는_상품_목록을_변경하기_전에_거절한다() {
        Event event = event(List.of(grand()));
        EventPrize original = event.getPrizes().getFirst();

        assertThatThrownBy(() -> update(event, List.of(
                new PrizeConfig("GRAND", "수정", 1, 99, 9), grand())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getPrizes()).containsExactly(original);
        assertThat(original.toConfig()).isEqualTo(grand());
    }

    @Test
    void DB_유니크_키와_같이_대소문자만_다른_중복도_거절한다() {
        Event event = event(List.of(grand()));
        assertThatThrownBy(() -> update(event, List.of(grand(),
                new PrizeConfig("grand", "중복", 2, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getPrizeConfigs()).containsExactly(grand());
    }

    @Test
    void 상품_전체_교체와_빈_목록을_허용한다() {
        Event event = event(List.of(grand()));
        PrizeConfig replacement = new PrizeConfig("NEW", "신규", 1, 1, 1);
        update(event, List.of(replacement));
        assertThat(event.getPrizeConfigs()).containsExactly(replacement);
        update(event, List.of());
        assertThat(event.getPrizes()).isEmpty();
    }

    @Test
    void 총_수량_부족과_가중치_합계_overflow는_상품_변경_전에_거절한다() {
        Event event = event(List.of(grand()));
        EventPrize original = event.getPrizes().getFirst();
        assertThatThrownBy(() -> event.update("이벤트", null, START, END, 3,
                DrawMethod.WEIGHTED, List.of(grand())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(event, List.of(
                new PrizeConfig("GRAND", "대상", 1, Long.MAX_VALUE, 5),
                new PrizeConfig("NEW", "신규", 2, 1, 1))))
                .isInstanceOf(ArithmeticException.class);
        assertThat(event.getPrizes()).containsExactly(original);
        assertThat(original.toConfig()).isEqualTo(grand());
    }

    @Test
    void 다른_Event에_같은_키가_있어도_상품_변경을_공유하지_않는다() {
        Event event = event(List.of(grand()));
        Event other = event(List.of(grand()));
        update(event, List.of(new PrizeConfig("GRAND", "변경", 1, 20, 5)));
        assertThat(other.getPrizeConfigs()).containsExactly(grand());
        assertThat(other.getPrizes().getFirst().getEvent()).isSameAs(other);
    }

    private static PrizeConfig grand() {
        return new PrizeConfig("GRAND", "대상", 1, 10, 2);
    }

    private static Event event(List<PrizeConfig> prizes) {
        return new Event(1L, "이벤트", null, START, END, 1, DrawMethod.WEIGHTED,
                1L, UUID.randomUUID().toString(), prizes);
    }

    private static void update(Event event, List<PrizeConfig> prizes) {
        event.update("이벤트", null, START, END, 1, DrawMethod.WEIGHTED, prizes);
    }
}
