package kr.co.cking.subscriptionverification.presentation.dto;

import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;

import java.time.Instant;

public record CreatorYoutubeChannelResponse(
        Long creatorId,
        String channelName,
        String channelHandle,
        String channelUrl,
        Instant updatedAt
) {
    public static CreatorYoutubeChannelResponse from(CreatorYoutubeChannel channel) {
        return new CreatorYoutubeChannelResponse(
                channel.getCreatorId(),
                channel.getChannelName(),
                channel.getChannelHandle(),
                channel.getChannelUrl(),
                channel.getUpdatedAt());
    }
}
