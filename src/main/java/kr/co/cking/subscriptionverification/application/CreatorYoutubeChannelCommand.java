package kr.co.cking.subscriptionverification.application;

/** Creator YouTube 채널 설정의 전체 교체 입력이다. */
public record CreatorYoutubeChannelCommand(
        String channelName,
        String channelHandle
) {
}
