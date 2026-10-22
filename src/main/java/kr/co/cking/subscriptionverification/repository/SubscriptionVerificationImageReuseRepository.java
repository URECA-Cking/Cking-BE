package kr.co.cking.subscriptionverification.repository;

import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuse;
import org.springframework.data.jpa.repository.JpaRepository;

/** Verification별 이미지 재사용 감사 결과를 저장·조회한다. */
public interface SubscriptionVerificationImageReuseRepository
        extends JpaRepository<SubscriptionVerificationImageReuse, Long> {
}
