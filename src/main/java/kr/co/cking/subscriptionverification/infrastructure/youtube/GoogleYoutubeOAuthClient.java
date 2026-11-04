package kr.co.cking.subscriptionverification.infrastructure.youtube;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.application.YoutubeChannelOwnershipClient;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;

/** YouTube 소유 채널을 조회하고 OAuth 토큰은 저장하지 않는다. */
@Component
public class GoogleYoutubeOAuthClient implements YoutubeChannelOwnershipClient {

    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static final String AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CHANNELS_URL = "https://www.googleapis.com/youtube/v3/channels";

    private final String clientId;
    private final String clientSecret;
    private final String apiKey;
    private final String redirectUri;
    private final RestClient client;

    @Autowired
    public GoogleYoutubeOAuthClient(
            @Value("${cking.youtube-oauth.client-id:}") String clientId,
            @Value("${cking.youtube-oauth.client-secret:}") String clientSecret,
            @Value("${cking.youtube-oauth.api-key:}") String apiKey,
            @Value("${cking.youtube-oauth.redirect-uri:}") String redirectUri,
            @Qualifier("youtubeOAuthRestClient") RestClient client
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.apiKey = apiKey;
        this.redirectUri = redirectUri;
        this.client = client;
    }

    GoogleYoutubeOAuthClient(String clientId, String clientSecret, String redirectUri, RestClient client) {
        this(clientId, clientSecret, "", redirectUri, client);
    }

    GoogleYoutubeOAuthClient(String clientId, String clientSecret, String redirectUri) {
        this(clientId, clientSecret, "", redirectUri, RestClient.create());
    }

    @Override
    public String authorizationUrl(String state, String challenge) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("access_type", "online")
                .build().encode().toUriString();
    }

    public String exchangeCode(String code, String verifier) {
        requireConfigured();
        LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("code_verifier", verifier);
        try {
            TokenResponse token = client.post().uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body).retrieve().body(TokenResponse.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw unavailable();
            }
            return token.accessToken();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public List<Channel> ownedChannels(String accessToken) {
        List<Channel> owned = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;
        for (int page = 0; page < 20; page++) {
            UriComponentsBuilder url = UriComponentsBuilder.fromUriString(CHANNELS_URL)
                    .queryParam("part", "id,snippet").queryParam("mine", true)
                    .queryParam("maxResults", 50);
            if (pageToken != null) {
                url.queryParam("pageToken", pageToken);
            }
            ChannelsResponse result = channelsResponse(accessToken, url.build().encode().toUriString());
            owned.addAll(result.items() == null ? List.of() : result.items());
            pageToken = result.nextPageToken();
            if (pageToken == null || pageToken.isBlank()) {
                return owned;
            }
            if (!seenPageTokens.add(pageToken)) {
                throw unavailable();
            }
        }
        throw unavailable();
    }

    public List<Channel> channelsForHandle(String accessToken, String handle) {
        return channels(accessToken, UriComponentsBuilder.fromUriString(CHANNELS_URL)
                .queryParam("part", "id,snippet").queryParam("forHandle", handle).build().encode().toUriString());
    }

    @Override
    public OwnedChannel verify(String authorizationCode, String codeVerifier, String requestedHandle) {
        String accessToken = exchangeCode(authorizationCode, codeVerifier);
        List<Channel> byHandle = channelsForHandle(accessToken, requestedHandle);
        if (byHandle.size() != 1 || byHandle.getFirst().id() == null
                || !byHandle.getFirst().id().matches("UC[A-Za-z0-9_-]{22}")) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_MISMATCH);
        }
        Channel selected = byHandle.getFirst();
        long matchingOwned = ownedChannels(accessToken).stream()
                .filter(channel -> selected.id().equals(channel.id())).count();
        if (matchingOwned != 1 || selected.snippet() == null || selected.snippet().title() == null
                || selected.snippet().title().isBlank() || selected.snippet().title().length() > 100) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_MISMATCH);
        }
        return new OwnedChannel(selected.id(), selected.snippet().title(), requestedHandle);
    }

    @Override
    public String currentChannelId(String handle) {
        if (apiKey.isBlank()) {
            throw unavailable();
        }
        String url = UriComponentsBuilder.fromUriString(CHANNELS_URL)
                .queryParam("part", "id").queryParam("forHandle", handle)
                .build().encode().toUriString();
        try {
            ChannelsResponse response = client.get().uri(url)
                    .headers(headers -> headers.add("X-Goog-Api-Key", apiKey))
                    .retrieve().body(ChannelsResponse.class);
            if (response == null || response.items() == null || response.items().size() != 1) {
                return null;
            }
            return response.items().getFirst().id();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private List<Channel> channels(String accessToken, String url) {
        ChannelsResponse result = channelsResponse(accessToken, url);
        return result.items() == null ? List.of() : result.items();
    }

    private ChannelsResponse channelsResponse(String accessToken, String url) {
        try {
            ChannelsResponse result = client.get().uri(url)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve().body(ChannelsResponse.class);
            return result == null ? new ChannelsResponse(List.of(), null) : result;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank() || redirectUri.isBlank()) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_UNAVAILABLE);
    }

    public record Channel(String id, Snippet snippet) {
        public record Snippet(String title) {
        }
    }

    private record ChannelsResponse(List<Channel> items, String nextPageToken) {
    }

    private record TokenResponse(String access_token) {
        String accessToken() {
            return access_token;
        }
    }
}
