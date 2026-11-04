package kr.co.cking.subscriptionverification.application;

/** Provider 호출을 격리하고 Creator와 채널의 연결은 Application에서 관리한다. */
public interface YoutubeChannelOwnershipClient {
    String authorizationUrl(String state, String challenge);

    OwnedChannel verify(String authorizationCode, String codeVerifier, String requestedHandle);

    String currentChannelId(String handle);

    record OwnedChannel(String channelId, String channelName, String channelHandle) {
    }
}
