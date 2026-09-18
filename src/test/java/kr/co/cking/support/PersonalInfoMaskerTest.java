package kr.co.cking.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonalInfoMaskerTest {

    @Test
    void 이름의_가운데_글자를_마스킹한다() {
        assertThat(PersonalInfoMasker.maskName("권혁준")).isEqualTo("권*준");
        assertThat(PersonalInfoMasker.maskName("김수")).isEqualTo("김수");
        assertThat(PersonalInfoMasker.maskName("김")).isEqualTo("*");
    }

    @Test
    void 전화번호의_가운데_자리를_마스킹한다() {
        assertThat(PersonalInfoMasker.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
        assertThat(PersonalInfoMasker.maskPhone("02-123-4567")).isEqualTo("02-123-4567");
    }
}
