package kr.co.cking.subscriptionverification.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorYoutubeChannelRepository extends JpaRepository<CreatorYoutubeChannel, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreatorYoutubeChannel c where c.creatorId = :creatorId")
    Optional<CreatorYoutubeChannel> findByCreatorIdForUpdate(@Param("creatorId") Long creatorId);

    boolean existsByChannelHandleAndCreatorIdNot(String channelHandle, Long creatorId);
}
