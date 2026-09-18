package kr.co.cking.drawing.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 외부 Drawing 공개 유스케이스의 진입점이다.
 *
 * <p>Drawing 공개와 후속 Notification 생성은 이 서비스의 Transaction 안에서 조합한다.
 * 현재 공개 상태 전이는 {@link DrawingPublicationService}에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class PublicationService {

    private final DrawingPublicationService drawingPublicationService;

    @Transactional
    public DrawingPublicationResult publish(Long drawingId, Long adminId) {
        return drawingPublicationService.publish(drawingId, adminId);
    }
}
