package kr.co.cking.interest.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.interest.application.dto.MemberInterests;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestPolicy;
import kr.co.cking.interest.domain.InterestSubtopic;
import kr.co.cking.interest.domain.InterestSubtopicId;
import kr.co.cking.interest.domain.InterestSubtopicSet;
import kr.co.cking.interest.domain.MemberInterest;
import kr.co.cking.interest.domain.MemberInterestSubtopic;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestSubtopicRepository;
import kr.co.cking.interest.repository.InterestSubtopicSetRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.interest.repository.MemberInterestRepository;
import kr.co.cking.interest.repository.MemberInterestSubtopicRepository;
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
    private final InterestSubtopicSetRepository subtopicSetRepository;
    private final InterestSubtopicRepository subtopicRepository;
    private final MemberInterestSubtopicRepository memberSubtopicRepository;

    /** 추천 조건용이다. 분류체계 교체·분야 비활성으로 더는 고를 수 없는 과거 선택은 뺀다. */
    public Set<String> findSelectableCodes(Long memberId) {
        return Set.copyOf(memberInterestRepository.findSelectableInterestCodes(memberId));
    }

    /** 선택이 없으면 활성 분류체계·세부 세트 버전과 빈 목록을 반환한다. */
    public MemberInterests findMine(Long memberId) {
        List<MemberInterest> selected = memberInterestRepository.findByMemberIdOrderByDisplayOrder(memberId);
        List<MemberInterestSubtopic> subtopics = memberSubtopicRepository.findByMemberIdOrderByDisplayOrder(memberId);
        String taxonomyVersion = selected.isEmpty()
                ? taxonomyRepository.findByActiveTrue().map(taxonomy -> taxonomy.getTaxonomyVersion()).orElse(null)
                : selected.get(0).getTaxonomyVersion();
        String subtopicVersion = subtopics.isEmpty()
                ? subtopicSetRepository.findByActiveTrue()
                        .filter(set -> set.getParentTaxonomyVersion().equals(taxonomyVersion))
                        .map(InterestSubtopicSet::getSubtopicVersion)
                        .orElse(null)
                : subtopics.get(0).getSubtopicVersion();
        return new MemberInterests(
                taxonomyVersion,
                selected.stream().map(MemberInterest::getInterestCode).toList(),
                subtopicVersion,
                subtopics.stream().map(MemberInterestSubtopic::getSubtopicCode).toList());
    }

    /** 세부 선택은 건드리지 않고(상위 해제에 따른 연쇄 삭제만) 상위 선택을 교체한다. */
    @Transactional
    public MemberInterests replace(Long memberId, String taxonomyVersion, List<String> interestCodes) {
        return replace(memberId, taxonomyVersion, interestCodes, null, null);
    }

    /**
     * 선택 전체를 교체한다. 빈 목록은 전체 해제다. 회원 행을 잠가 같은 회원의 동시 저장을 직렬화하므로 선택이 상한을
     * 넘지 않고 최종 상태는 마지막 요청의 목록이다. 유지되는 선택은 {@code selectedAt}을 그대로 둔다.
     *
     * <p>세부 주제는 버전·코드를 모두 생략하면 기존 선택을 유지하고(상위가 해제된 것만 삭제), 보내면 빈 목록을 포함해
     * 전체를 교체한다. 같은 Transaction에서 처리하므로 새 잠금은 없다.
     */
    @Transactional
    public MemberInterests replace(
            Long memberId,
            String taxonomyVersion,
            List<String> interestCodes,
            String subtopicVersion,
            List<String> subtopicCodes
    ) {
        Set<String> requested = requireValidShape(interestCodes);
        boolean subtopicsGiven = subtopicVersion != null || subtopicCodes != null;
        Set<String> requestedSubtopics = subtopicsGiven ? requireValidSubtopicShape(subtopicVersion, subtopicCodes) : null;
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        requireSelectable(taxonomyVersion, requested);
        if (requestedSubtopics != null) {
            requireSelectableSubtopics(taxonomyVersion, requested, subtopicVersion, requestedSubtopics);
        }

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
        replaceSubtopics(memberId, taxonomyVersion, requested, subtopicVersion, requestedSubtopics);
        memberInterestRepository.flush();

        return findMine(memberId);
    }

    private void replaceSubtopics(
            Long memberId, String taxonomyVersion, Set<String> parents, String subtopicVersion, Set<String> requested
    ) {
        Set<InterestSubtopicId> target = new LinkedHashSet<>();
        if (requested != null) {
            requested.forEach(code -> target.add(new InterestSubtopicId(subtopicVersion, code)));
        }
        List<MemberInterestSubtopic> existing = memberSubtopicRepository.findByMemberIdOrderByDisplayOrder(memberId);
        if (requested == null) { // 생략: 상위가 유지되는 기존 선택만 남긴다
            Map<InterestSubtopicId, InterestSubtopic> catalog = subtopicRepository.findAllById(existing.stream()
                    .map(selection -> new InterestSubtopicId(selection.getSubtopicVersion(), selection.getSubtopicCode()))
                    .toList()).stream().collect(Collectors.toMap(InterestSubtopic::getId, subtopic -> subtopic));
            for (MemberInterestSubtopic selection : existing) {
                InterestSubtopicId id = new InterestSubtopicId(selection.getSubtopicVersion(), selection.getSubtopicCode());
                InterestSubtopic subtopic = catalog.get(id);
                if (subtopic.getParentTaxonomyVersion().equals(taxonomyVersion)
                        && parents.contains(subtopic.getParentInterestCode())) {
                    target.add(id);
                }
            }
        }

        Set<InterestSubtopicId> kept = new HashSet<>();
        List<MemberInterestSubtopic> removed = existing.stream()
                .filter(selection -> {
                    InterestSubtopicId id = new InterestSubtopicId(selection.getSubtopicVersion(), selection.getSubtopicCode());
                    boolean keep = target.contains(id);
                    if (keep) {
                        kept.add(id);
                    }
                    return !keep;
                })
                .toList();
        memberSubtopicRepository.deleteAll(removed);
        memberSubtopicRepository.flush(); // 버전이 바뀌는 교체에서 삭제가 삽입보다 먼저 반영되게 한다

        Instant now = Instant.now();
        memberSubtopicRepository.saveAll(target.stream()
                .filter(id -> !kept.contains(id))
                .map(id -> new MemberInterestSubtopic(memberId, id.getSubtopicVersion(), id.getSubtopicCode(), now))
                .toList());
    }

    /** 버전과 코드는 함께 보내야 한다. 중복·null·blank와 상위 3개 × 3개를 넘는 목록은 거부한다. */
    private Set<String> requireValidSubtopicShape(String subtopicVersion, List<String> subtopicCodes) {
        if (subtopicVersion == null || subtopicVersion.isBlank() || subtopicCodes == null
                || subtopicCodes.size() > InterestPolicy.MAX_SELECTION * InterestPolicy.MAX_SUBTOPIC_PER_PARENT
                || subtopicCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        Set<String> distinct = new LinkedHashSet<>(subtopicCodes);
        if (distinct.size() != subtopicCodes.size()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return distinct;
    }

    /** 이번 상위 분류체계에 묶인 활성 세트의 활성 세부만, 이번 요청에서 고른 상위 아래에 상위당 상한 이내로 고를 수 있다. */
    private void requireSelectableSubtopics(
            String taxonomyVersion, Set<String> parents, String subtopicVersion, Set<String> requested
    ) {
        boolean activeSet = subtopicSetRepository.findById(subtopicVersion)
                .filter(set -> set.isActive() && set.getParentTaxonomyVersion().equals(taxonomyVersion))
                .isPresent();
        if (!activeSet) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        Map<String, String> parentByCode = new HashMap<>();
        for (InterestSubtopic subtopic : subtopicRepository.findActiveBySubtopicVersion(subtopicVersion)) {
            parentByCode.put(subtopic.getSubtopicCode(), subtopic.getParentInterestCode());
        }
        Map<String, Integer> perParent = new HashMap<>();
        for (String code : requested) {
            String parent = parentByCode.get(code);
            if (parent == null || !parents.contains(parent)
                    || perParent.merge(parent, 1, Integer::sum) > InterestPolicy.MAX_SUBTOPIC_PER_PARENT) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            }
        }
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
}
