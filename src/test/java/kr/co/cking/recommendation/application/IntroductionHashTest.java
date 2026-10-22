package kr.co.cking.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 배치와 같은 소개 지문을 계산하는지 경계 문자로 확인한다. 기대값은 배치의 CreatorProfile.text와 text_hash로 계산했다. */
class IntroductionHashTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void 배치와_같은_소개_지문을_계산한다(String name, String raw, String expected) {
        assertThat(RecommendationEligibilityService.introductionHash(raw)).isEqualTo(expected);
    }

    static Stream<Arguments> vectors() {
        return Stream.of(
                Arguments.of("CRLF·CR은 LF로 바꾸고 양끝 공백을 지운다", "  요리 소개\r\n둘째 줄\r  ",
                        "d7773419e9932e7620a36ac89949483186310f4b3160ddf3dfc34b2de6ee7d32"),
                Arguments.of("NBSP·U+3000·U+2028·U+0085를 지운다", " 　요리 \u0085",
                        "aa664b105a07ea19ff3e53aaa660b3ef00bfdaec94457acf820bf131210646a1"),
                Arguments.of("U+001C부터 U+001F까지 지운다", "\u001c\u001d요리\u001e\u001f",
                        "aa664b105a07ea19ff3e53aaa660b3ef00bfdaec94457acf820bf131210646a1"),
                Arguments.of("U+200B는 공백이 아니라 남긴다", "​요리​",
                        "faf14dfcf835c9712efb01f710f06182dd575b6e61469cab7ed1535508fc6c19"),
                Arguments.of("NFC는 그대로 쓴다", "가",
                        "64ee5293d31bc58b72d76ae9a86a902e90442428e29f24f4ecb257ba841cdd36"),
                Arguments.of("NFD도 정규화하지 않고 그대로 쓴다", "가",
                        "a0d2271e0fe8929d025eb859cde49251ba70c86abb76123f03f8d8eda1c98939"),
                Arguments.of("공백만 있으면 빈 문자열의 지문이다", "\t \n　",
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }
}
