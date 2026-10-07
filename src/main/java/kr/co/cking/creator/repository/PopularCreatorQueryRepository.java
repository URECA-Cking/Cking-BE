package kr.co.cking.creator.repository;

import java.util.List;
import kr.co.cking.creator.domain.CreatorSpace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 개인화 추천이 비었을 때 쓰는 인기순 조회다. 인기는 팔로워 수이고 Creator Space가 있어 카드를 만들 수 있는 Creator만 대상이다. */
public interface PopularCreatorQueryRepository extends Repository<CreatorSpace, Long> {

    /**
     * 팔로워 수 내림차순, 같으면 creatorId 오름차순이다. 팔로워가 0명인 Creator도 포함한다.
     * ponytail: 요청마다 creator_follow를 집계한다. Creator·팔로우가 수만 건을 넘으면 집계 컬럼이나 캐시로 옮긴다.
     */
    @Query("""
            select new kr.co.cking.creator.repository.PopularCreatorCandidate(
                c.creatorId, c.name, space.introText, space.profileImageUrl, count(f.followId)
            )
            from CreatorSpace space
            join Creator c on c.creatorId = space.creatorId
            left join CreatorFollow f on f.creatorId = c.creatorId
            group by c.creatorId, c.name, space.introText, space.profileImageUrl
            order by count(f.followId) desc, c.creatorId asc
            """)
    List<PopularCreatorCandidate> findPopular(Pageable pageable);
}
