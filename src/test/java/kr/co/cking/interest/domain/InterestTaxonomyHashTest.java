package kr.co.cking.interest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import org.junit.jupiter.api.Test;

/**
 * 기대값은 같은 규칙을 따로 구현한 Python(`json.dumps(ensure_ascii=False, separators=(",", ":"))`)으로 계산했다.
 * Cking-LLM이 제공하는 fixture가 병합되면 같은 사례를 그쪽 값과도 대조한다.
 */
class InterestTaxonomyHashTest {

    @Test
    void ASCII_한_행의_canonical_JSON과_해시() {
        List<Row> rows = List.of(new Row("A", "Alpha", "first"));

        assertThat(InterestTaxonomyHash.canonicalJson(rows))
                .isEqualTo("{\"categories\":[{\"code\":\"A\",\"name\":\"Alpha\",\"description\":\"first\"}]}");
        assertThat(InterestTaxonomyHash.compute(rows))
                .isEqualTo("03673e5fdb05fd7ce49c482f79249ab09db31ff483427ce8b98ead84de7f2f02");
    }

    @Test
    void 한글은_이스케이프하지_않고_행_순서를_유지한다() {
        List<Row> rows = List.of(
                new Row("FITNESS", "운동·건강", "헬스, 웨이트 트레이닝, 홈트레이닝, 요가, 필라테스"),
                new Row("FOOD", "요리·푸드", "요리 레시피, 집밥, 베이킹, 먹방, 맛집 리뷰"));

        assertThat(InterestTaxonomyHash.compute(rows))
                .isEqualTo("e97bd72473f2cc6ab5bc7dfa0de091c193342b81ca92791195850dbcbe992031");
    }

    @Test
    void 행_순서가_바뀌면_해시가_달라진다() {
        List<Row> rows = List.of(new Row("A", "a", "a"), new Row("B", "b", "b"));
        List<Row> reversed = List.of(new Row("B", "b", "b"), new Row("A", "a", "a"));

        assertThat(InterestTaxonomyHash.compute(rows)).isNotEqualTo(InterestTaxonomyHash.compute(reversed));
    }

    @Test
    void 앞뒤_공백_탭_줄바꿈만_제거하고_내부_공백과_탭은_보존한다() {
        List<Row> rows = List.of(new Row("  FOOD\t", "\n요리·푸드 \t", " \t집밥  레시피,\t 베이킹 \n"));

        assertThat(InterestTaxonomyHash.canonicalJson(rows)).isEqualTo(
                "{\"categories\":[{\"code\":\"FOOD\",\"name\":\"요리·푸드\",\"description\":\"집밥  레시피,\\t 베이킹\"}]}");
        assertThat(InterestTaxonomyHash.compute(rows))
                .isEqualTo("3fcb6c2a6f3f3f47f04bee345c11dcb3ead203af4203a868f7aec8806e8b34d2");
    }

    @Test
    void CRLF와_CR은_LF로_통일한다() {
        List<Row> rows = List.of(new Row("GAME", "게임", "\r\n게임 플레이\r\n공략\r리뷰\r\n"));

        assertThat(InterestTaxonomyHash.canonicalJson(rows)).contains("\"description\":\"게임 플레이\\n공략\\n리뷰\"");
        assertThat(InterestTaxonomyHash.compute(rows))
                .isEqualTo("391554c1a598e350760b27e3b0d04f97b6bcb4e7c6895aec668b8ae523bc648f");
    }

    @Test
    void NFD_입력은_NFC와_같은_해시가_된다() {
        String nfc = "반려동물";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);

        String expected = "4d6a70e9b0f71ce65c720528a390832315305020db5900fe026b08b17851b34b";
        assertThat(InterestTaxonomyHash.compute(List.of(new Row("PET", nfc, "한글 정규화")))).isEqualTo(expected);
        assertThat(InterestTaxonomyHash.compute(List.of(
                new Row("PET", nfd, Normalizer.normalize("한글 정규화", Normalizer.Form.NFD))))).isEqualTo(expected);
    }

    @Test
    void 따옴표_역슬래시_제어문자는_Python과_같이_이스케이프한다() {
        List<Row> rows = List.of(new Row("Q", "a\"b\\c", "tab\there\u0001ctl/slash"));

        assertThat(InterestTaxonomyHash.canonicalJson(rows)).contains("\"name\":\"a\\\"b\\\\c\"")
                .contains("tab\\there\\u0001ctl/slash");
        assertThat(InterestTaxonomyHash.compute(rows))
                .isEqualTo("5934c4af2f28ad21b68b6bce34b2f3b383199b9e0d6bb4f12c8edbef3c6f757a");
    }

    @Test
    void NBSP와_전각_공백은_제거하지_않는다() {
        List<Row> rows = List.of(new Row("N", " 이름 ", "　전각공백　"));

        assertThat(InterestTaxonomyHash.compute(rows))
                .isEqualTo("d93a45ce33a7c54ffc2cf7582e3588e0c85053889e341d569736c903c624b21f");
    }
}
