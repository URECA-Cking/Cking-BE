package kr.co.cking.subscriptionverification.application;

import kr.co.cking.mission.Mission;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;

record SubscriptionVerificationSubmissionSource(
        Mission mission,
        CreatorYoutubeChannel channel
) {}
