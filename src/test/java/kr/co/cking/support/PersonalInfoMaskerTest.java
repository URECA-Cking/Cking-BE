package kr.co.cking.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonalInfoMaskerTest {

    @Test
    void 이름의_가운데_글자를_마스킹한다() {
        assertThat(PersonalInfoMasker.maskName("권혁준")).isEqualTo("권*준");
        assertThat(PersonalInfoMasker.maskName("김수")).isEqualTo("김*");
        assertThat(PersonalInfoMasker.maskName("김")).isEqualTo("*");
    }

    @Test
    void 서울과_휴대전화번호의_가운데_자리를_마스킹한다() {
        assertThat(PersonalInfoMasker.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
        assertThat(PersonalInfoMasker.maskPhone("02-123-4567")).isEqualTo("02-****-4567");
        assertThat(PersonalInfoMasker.maskPhone("02-1234-5678")).isEqualTo("02-****-5678");
    }

    @Test
    void 형식을_판별할_수_없는_전화번호는_원문을_노출하지_않는다() {
        assertThat(PersonalInfoMasker.maskPhone("01012345678")).isEqualTo("****");
        assertThat(PersonalInfoMasker.maskPhone("잘못된 번호")).isEqualTo("****");
    }
}
