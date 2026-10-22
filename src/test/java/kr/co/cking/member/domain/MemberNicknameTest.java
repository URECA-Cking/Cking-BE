package kr.co.cking.member.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberNicknameTest {

    @Test
    void NFC_정규화와_ROOT_소문자_키로_닉네임을_만든다() {
        MemberNickname nickname = MemberNickname.of("Cafe-가");

        assertThat(nickname.value()).isEqualTo("Cafe-가");
        assertThat(nickname.normalized()).isEqualTo("cafe-가");
    }

    @Test
    void 완성형_한글_ASCII영문숫자_밑줄_하이픈만_허용한다() {
        for (String invalid : new String[] {"", "닉 네임", "ㅂㅅ", "ＡＢＣ", "emoji😀", "a".repeat(21)}) {
            assertThatThrownBy(() -> MemberNickname.of(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 최초_설정은_변경시각을_만들지_않고_실제_변경부터_14일_제한을_시작한다() {
        Member member = new Member("OAuth 이름", null, null, MemberRole.USER);
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 0, 0);

        member.setInitialNickname(MemberNickname.of("first"));
        assertThat(member.getNicknameChangedAt()).isNull();
        assertThat(member.canChangeNicknameAt(now)).isTrue();

        member.changeNickname(MemberNickname.of("second"), now);
        assertThat(member.nicknameChangeableAt()).isEqualTo(now.plusDays(14));
        assertThat(member.canChangeNicknameAt(now.plusDays(14).minusNanos(1))).isFalse();
        assertThat(member.canChangeNicknameAt(now.plusDays(14))).isTrue();
    }

    @Test
    void 복귀는_현재와_이전_닉네임을_교체하고_변경시각을_유지한다() {
        Member member = new Member("OAuth 이름", null, null, MemberRole.USER);
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 12, 0, 0);
        member.setInitialNickname(MemberNickname.of("first"));
        member.changeNickname(MemberNickname.of("second"), changedAt);

        member.revertNickname();

        assertThat(member.getNickname()).isEqualTo("first");
        assertThat(member.getPreviousNickname()).isEqualTo("second");
        assertThat(member.getNicknameChangedAt()).isEqualTo(changedAt);
    }
}
