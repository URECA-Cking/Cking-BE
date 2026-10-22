package kr.co.cking.subscriptionverification.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CreatorYoutubeChannelRequest {

    private CreatorYoutubeChannelRequest() {
    }

    public record Put(
            @NotBlank @Size(max = 100) String channelName,
            @NotBlank @Size(max = 100) String channelHandle
    ) {
    }
}
