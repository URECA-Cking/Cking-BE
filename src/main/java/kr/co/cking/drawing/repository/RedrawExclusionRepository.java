package kr.co.cking.drawing.repository;

import kr.co.cking.drawing.domain.RedrawExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

/** REDRAW Drawing 입력의 제외 명단과 사유를 영속화한다. */
public interface RedrawExclusionRepository extends JpaRepository<RedrawExclusion, Long> {
}
