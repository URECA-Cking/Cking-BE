package kr.co.cking.stream.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamType;

public interface DeadStreamMessageRepository extends JpaRepository<DeadStreamMessage, Long> {

    Optional<DeadStreamMessage> findBySourceStreamIdAndStreamType(String sourceStreamId, DeadStreamType streamType);
}
