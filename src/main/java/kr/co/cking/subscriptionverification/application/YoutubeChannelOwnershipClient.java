package kr.co.cking.subscriptionverification.application;

/** Provider interaction stays behind this port; application code owns the Creator binding. */
public interface YoutubeChannelOwnershipClient {
    String authorizationUrl(String state, String challenge);

    OwnedChannel verify(String authorizationCode, String codeVerifier, String requestedHandle);

    record OwnedChannel(String channelId, String channelName, String channelHandle) {
    }
}
