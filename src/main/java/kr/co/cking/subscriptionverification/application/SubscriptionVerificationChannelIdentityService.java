package kr.co.cking.subscriptionverification.application;

import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** VLM 처리 전에 현재 YouTube 핸들이 검증된 채널 ID를 가리키는지 확인한다. */
@Service
@RequiredArgsConstructor
public class SubscriptionVerificationChannelIdentityService {

    private final CreatorYoutubeChannelRepository channels;
    private final YoutubeChannelOwnershipClient ownershipClient;

    public boolean matchesCurrentChannel(Long creatorId, String frozenHandle) {
        CreatorYoutubeChannel channel = channels.findById(creatorId).orElse(null);
        // 배포 전 접수된 요청은 소유권 ID가 없어 기존 판정 흐름으로 마무리한다.
        if (channel == null || !channel.isVerified()) {
            return true;
        }
        return channel.getChannelHandle().equals(frozenHandle)
                && channel.getYoutubeChannelId().equals(ownershipClient.currentChannelId(frozenHandle));
    }
}
