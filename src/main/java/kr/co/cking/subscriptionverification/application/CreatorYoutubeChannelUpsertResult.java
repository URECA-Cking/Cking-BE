package kr.co.cking.subscriptionverification.application;

import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;

public record CreatorYoutubeChannelUpsertResult(
        CreatorYoutubeChannel channel,
        boolean created
) {
}
