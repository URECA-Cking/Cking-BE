package kr.co.cking.member.presentation.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.member.application.MemberProfileUpdateService;
import lombok.Getter;

/** 닉네임은 교체하고, 사진 키는 전달했을 때만 바꾼다. null을 명시하면 기본 이미지로 돌아간다. */
@Getter
public class MemberProfileRequest {

    @NotBlank
    @Size(max = 20)
    private String nickname;

    @Size(max = 200)
    private String profileImageKey;

    private boolean profileImageKeyProvided;

    @JsonSetter("nickname")
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @JsonSetter("profileImageKey")
    public void setProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
        this.profileImageKeyProvided = true;
    }

    public MemberProfileUpdateService.UpdateFields toFields() {
        return new MemberProfileUpdateService.UpdateFields(nickname, profileImageKey, profileImageKeyProvided);
    }
}
