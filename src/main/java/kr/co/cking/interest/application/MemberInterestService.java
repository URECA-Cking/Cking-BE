package kr.co.cking.interest.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.interest.application.dto.MemberInterests;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestPolicy;
import kr.co.cking.interest.domain.MemberInterest;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.interest.repository.MemberInterestRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원의 관심 분야 조회와 전체 교체 저장이다. 저장은 같은 요청을 반복해도 최종 상태가 같다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberInterestService {

    private final MemberRepository memberRepository;
    private final InterestTaxonomyRepository taxonomyRepository;
    private final InterestCategoryRepository categoryRepository;
    private final MemberInterestRepository memberInterestRepository;

    /** 선택이 없으면 활성 분류체계 버전과 빈 목록을 반환한다. */
    public MemberInterests findMine(Long memberId) {
        List<MemberInterest> selected = memberInterestRepository.findByMemberIdOrderByDisplayOrder(memberId);
        if (selected.isEmpty()) {
            return new MemberInterests(
                    taxonomyRepository.findByActiveTrue().map(taxonomy -> taxonomy.getTaxonomyVersion()).orElse(null),
                    List.of());
        }
        return toView(selected);
    }

    /**
     * 선택 전체를 교체한다. 빈 목록은 전체 해제다. 회원 행을 잠가 같은 회원의 동시 저장을 직렬화하므로 선택이 상한을
     * 넘지 않고 최종 상태는 마지막 요청의 목록이다. 유지되는 선택은 {@code selectedAt}을 그대로 둔다.
     */
    @Transactional
    public MemberInterests replace(Long memberId, String taxonomyVersion, List<String> interestCodes) {
        Set<String> requested = requireValidShape(interestCodes);
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        requireSelectable(taxonomyVersion, requested);

        List<MemberInterest> existing = memberInterestRepository.findByMemberIdOrderByDisplayOrder(memberId);
        Set<String> kept = new HashSet<>();
        List<MemberInterest> removed = existing.stream()
                .filter(selection -> {
                    boolean keep = selection.getTaxonomyVersion().equals(taxonomyVersion)
                            && requested.contains(selection.getInterestCode());
                    if (keep) {
                        kept.add(selection.getInterestCode());
                    }
                    return !keep;
                })
                .toList();
        memberInterestRepository.deleteAll(removed);

        Instant now = Instant.now();
        memberInterestRepository.saveAll(requested.stream()
                .filter(code -> !kept.contains(code))
                .map(code -> new MemberInterest(memberId, taxonomyVersion, code, now))
                .toList());
        memberInterestRepository.flush();

        return findMine(memberId);
    }

    private Set<String> requireValidShape(List<String> interestCodes) {
        if (interestCodes == null || interestCodes.size() > InterestPolicy.MAX_SELECTION) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        Set<String> distinct = new LinkedHashSet<>(interestCodes);
        if (distinct.size() != interestCodes.size() || distinct.contains(null)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return distinct;
    }

    /** 등록된 활성 분류체계의 활성 분야만 고를 수 있다. */
    private void requireSelectable(String taxonomyVersion, Set<String> requested) {
        boolean activeTaxonomy = taxonomyRepository.findById(taxonomyVersion)
                .filter(taxonomy -> taxonomy.isActive())
                .isPresent();
        if (!activeTaxonomy) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        Set<String> selectable = new HashSet<>();
        for (InterestCategory category : categoryRepository.findActiveByTaxonomyVersion(taxonomyVersion)) {
            selectable.add(category.getInterestCode());
        }
        if (!selectable.containsAll(requested)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private MemberInterests toView(List<MemberInterest> selected) {
        return new MemberInterests(
                selected.get(0).getTaxonomyVersion(),
                selected.stream().map(MemberInterest::getInterestCode).toList());
    }
}
