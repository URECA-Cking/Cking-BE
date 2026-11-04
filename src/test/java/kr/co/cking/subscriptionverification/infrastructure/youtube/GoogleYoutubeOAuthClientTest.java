package kr.co.cking.subscriptionverification.infrastructure.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class GoogleYoutubeOAuthClientTest {

    private final GoogleYoutubeOAuthClient client = spy(new GoogleYoutubeOAuthClient(
            "google-client", "google-secret", "https://dev-api.cking.co.kr/login/oauth2/code/youtube"));


    @Test
    void 핸들_채널이_로그인_계정의_소유_목록에_있을_때만_확인한다() {
        doReturn("access-token").when(client).exchangeCode(anyString(), anyString());
        doReturn(List.of(channel("UCabcdefghijklmnopqrstuv", "공식 채널")))
                .when(client).channelsForHandle(anyString(), anyString());
        doReturn(List.of(channel("UCabcdefghijklmnopqrstuv", "공식 채널")))
                .when(client).ownedChannels(anyString());

        var owned = client.verify("code", "verifier", "@official");

        assertThat(owned.channelId()).isEqualTo("UCabcdefghijklmnopqrstuv");
        assertThat(owned.channelName()).isEqualTo("공식 채널");
    }

    @Test
    void 소유_목록에_여러_채널이_있어도_입력_handle의_ID를_고른다() {
        doReturn("access-token").when(client).exchangeCode(anyString(), anyString());
        doReturn(List.of(channel("UCabcdefghijklmnopqrstuv", "선택 채널")))
                .when(client).channelsForHandle(anyString(), anyString());
        doReturn(List.of(channel("UC0000000000000000000000", "다른 채널"),
                channel("UCabcdefghijklmnopqrstuv", "선택 채널")))
                .when(client).ownedChannels(anyString());

        assertThat(client.verify("code", "verifier", "@selected").channelId())
                .isEqualTo("UCabcdefghijklmnopqrstuv");
    }

    @Test
    void 핸들_채널이_소유_목록에_없으면_거절한다() {
        doReturn("access-token").when(client).exchangeCode(anyString(), anyString());
        doReturn(List.of(channel("UCabcdefghijklmnopqrstuv", "타인 채널")))
                .when(client).channelsForHandle(anyString(), anyString());
        doReturn(List.of(channel("UC0000000000000000000000", "내 채널")))
                .when(client).ownedChannels(anyString());

        assertThatThrownBy(() -> client.verify("code", "verifier", "@other"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_MISMATCH);
    }

    @Test
    void 소유_채널이_없어도_입력_handle을_검증할_수_없다() {
        doReturn("access-token").when(client).exchangeCode(anyString(), anyString());
        doReturn(List.of(channel("UCabcdefghijklmnopqrstuv", "채널")))
                .when(client).channelsForHandle(anyString(), anyString());
        doReturn(List.of()).when(client).ownedChannels(anyString());

        assertThatThrownBy(() -> client.verify("code", "verifier", "@other"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_MISMATCH);
    }

    @Test
    void handle_조회가_여러_건이면_첫_채널을_임의로_선택하지_않는다() {
        doReturn("access-token").when(client).exchangeCode(anyString(), anyString());
        doReturn(List.of(channel("UCabcdefghijklmnopqrstuv", "첫 채널"),
                channel("UC0000000000000000000000", "두 번째 채널")))
                .when(client).channelsForHandle(anyString(), anyString());

        assertThatThrownBy(() -> client.verify("code", "verifier", "@ambiguous"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_MISMATCH);
    }

    @Test
    void Google_토큰과_channels_응답을_채널_ID로_대조한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleYoutubeOAuthClient real = new GoogleYoutubeOAuthClient("id", "secret",
                "https://dev-api.cking.co.kr/login/oauth2/code/youtube", builder.build());
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"access_token\":\"short-lived-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&forHandle=@official"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"UCabcdefghijklmnopqrstuv\","
                        + "\"snippet\":{\"title\":\"Official\"}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true&maxResults=50"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"UCabcdefghijklmnopqrstuv\","
                        + "\"snippet\":{\"title\":\"Official\"}}]}", MediaType.APPLICATION_JSON));

        assertThat(real.verify("authorization-code", "pkce-verifier", "@official").channelId())
                .isEqualTo("UCabcdefghijklmnopqrstuv");
        server.verify();
    }

    @Test
    void mine_조회가_여러_페이지면_다음_페이지의_채널도_확인한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleYoutubeOAuthClient real = new GoogleYoutubeOAuthClient("id", "secret", "redirect", builder.build());
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true&maxResults=50"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"UC0000000000000000000000\"}],"
                        + "\"nextPageToken\":\"NEXT\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true&maxResults=50&pageToken=NEXT"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"UCabcdefghijklmnopqrstuv\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(real.ownedChannels("access-token")).extracting(
                GoogleYoutubeOAuthClient.Channel::id)
                .containsExactly("UC0000000000000000000000", "UCabcdefghijklmnopqrstuv");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void YouTube_오류는_토큰을_노출하지_않고_일시적_연결_실패로_처리한다(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleYoutubeOAuthClient real = new GoogleYoutubeOAuthClient("id", "secret", "redirect", builder.build());
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true&maxResults=50"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> real.ownedChannels("short-lived-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_UNAVAILABLE);
        server.verify();
    }

    @Test
    void YouTube_요청_타임아웃도_일시적_실패로_처리한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleYoutubeOAuthClient real = new GoogleYoutubeOAuthClient("id", "secret", "redirect", builder.build());
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true&maxResults=50"))
                .andRespond(withException(new IOException("timeout")));

        assertThatThrownBy(() -> real.ownedChannels("short-lived-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_UNAVAILABLE);
        server.verify();
    }

    @Test
    void 현재_핸들이_가리키는_채널_ID를_공개_API로_재확인한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleYoutubeOAuthClient real = new GoogleYoutubeOAuthClient("id", "secret", "api-key", "redirect",
                builder.build());
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/channels?part=id&forHandle=@official&key=api-key"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"UCabcdefghijklmnopqrstuv\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(real.currentChannelId("@official")).isEqualTo("UCabcdefghijklmnopqrstuv");
        server.verify();
    }

    private GoogleYoutubeOAuthClient.Channel channel(String id, String title) {
        return new GoogleYoutubeOAuthClient.Channel(id,
                new GoogleYoutubeOAuthClient.Channel.Snippet(title));
    }
}
