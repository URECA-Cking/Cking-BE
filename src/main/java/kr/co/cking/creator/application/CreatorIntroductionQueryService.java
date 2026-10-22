package kr.co.cking.creator.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import kr.co.cking.creator.repository.CreatorIntroText;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.recommendation.application.CreatorIntroductions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 추천 자격 검사가 현재 소개를 확인할 수 있게 Creator Space의 소개 원문을 읽어 준다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorIntroductionQueryService implements CreatorIntroductions {

    private final CreatorSpaceRepository spaceRepository;

    @Override
    public Map<Long, String> findIntroTexts(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Map.of();
        }
        return spaceRepository.findIntroTextsByCreatorIdIn(creatorIds).stream()
                .collect(Collectors.toMap(CreatorIntroText::creatorId,
                        row -> row.introText() == null ? "" : row.introText()));
    }
}
