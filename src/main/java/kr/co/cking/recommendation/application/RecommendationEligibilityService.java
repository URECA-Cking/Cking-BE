package kr.co.cking.recommendation.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.recommendation.domain.RecommendationErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 후보가 지금 공개해도 되는지 판단하는 근거를 세대와 함께 보존하고 조회 때 검사한다.
 * 모델 판단은 배치의 책임이고, BE는 근거의 완전성·분야 일치·현재 소개만 검증한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEligibilityService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EMPTY_HASH = introductionHash("");
    private static final String HASH_PATTERN = "[0-9a-f]{64}";
    private static final int MAX_PROFILES = 101;
    private static final int MAX_TAGS = 3;

    private final JdbcTemplate jdbc;
    private final TaxonomyCodes taxonomyCodes;
    private final CreatorIntroductions introductions;

    /**
     * 세대와 근거는 한 번 저장되면 바뀌지 않으므로 파싱한 결과를 세대별로 보관한다. 값이 비어 있으면 근거를 버린 세대다.
     * 소개 지문은 요청마다 현재 값과 비교하므로 보관하지 않는다.
     */
    private final Cache<String, Optional<Map<Long, Profile>>> parsedProofs = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /** 검사할 추천 한 건이다. {@code seed}는 유사 추천에서만 있고, {@code interests}가 비면 분야 조건이 없다. */
    public record Ref(long generation, Long seed, long candidate, Set<String> interests) { }

    private record Profile(String introductionHash, Set<String> tags) { }

    /** 배치가 계산하는 소개 지문과 같은 규칙이다. CRLF·CR을 LF로 바꾸고 양끝의 공백 문자를 지운 문자열의 SHA-256이다. */
    public static String introductionHash(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int end = text.length();
        while (start < end && isWhitespace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isWhitespace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.substring(start, end).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }

    private static BusinessException invalid() {
        return new BusinessException(RecommendationErrorCode.INVALID_RECOMMENDATION_RESULT);
    }

    /** 근거를 저장하기 전에 형식·분야·후보 구성이 맞는지 검사한다. 근거가 없는 구 적재는 통과시킨다. */
    public void validateForStorage(JsonNode proof, Long seed, Set<Long> candidates, String interest) {
        if (proof == null) {
            return; // 구 클라이언트 호환. save에서 관측 가능한 경고를 남긴다.
        }
        Map<Long, Profile> profiles = profiles(proof, taxonomy(proof, true, new HashMap<>()));
        Set<Long> expected = new TreeSet<>(candidates);
        if (seed != null) {
            expected.add(seed);
        }
        if (!profiles.keySet().equals(expected)) {
            throw invalid();
        }
        // 저장 시점에는 배치가 보낸 지문을 그대로 믿는다. 현재 소개와 다르면 조회에서 개별 후보만 제외된다.
        Map<Long, String> hashes = new HashMap<>();
        profiles.forEach((id, profile) -> hashes.put(id, profile.introductionHash()));
        Set<String> interests = interest == null ? Set.of() : Set.of(interest);
        for (long candidate : candidates) {
            if (!matches(new Ref(0, seed, candidate, interests), profiles, hashes)) {
                throw invalid();
            }
        }
    }

    /** 세대와 같은 트랜잭션에서 근거를 저장한다. 근거가 없으면 저장하지 않고 경고만 남기며, 그 세대는 조회에서 제외된다. */
    public void save(String kind, long generation, JsonNode proof, Long seed, Set<Long> candidates, String interest) {
        if (proof == null) {
            log.warn("RECOMMENDATION_PROOF_MISSING sourceKind={} generationId={} candidateCount={} visible=false",
                    kind, generation, candidates.size());
            return;
        }
        validateForStorage(proof, seed, candidates, interest);
        jdbc.update("INSERT INTO recommendation_eligibility(source_kind, generation_id, proof) VALUES(?, ?, ?)",
                kind, generation, proof.toString());
        warnIfStale(kind, generation, proof);
    }

    /** 소개 지문은 배치가 계산한 값이라 계산 뒤 소개가 바뀌었거나 지문이 틀리면 조회에서 조용히 제외된다. 적재 때 그 수를 남긴다. */
    private void warnIfStale(String kind, long generation, JsonNode proof) {
        Map<Long, String> stored = new HashMap<>();
        proof.path("profiles").forEach(row ->
                stored.put(row.path("creatorId").asLong(), row.path("introductionHash").asText()));
        Map<Long, String> current = currentHashes(stored.keySet());
        long stale = stored.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(current.get(entry.getKey())))
                .count();
        if (stale > 0) {
            log.warn("RECOMMENDATION_PROOF_STALE sourceKind={} generationId={} staleProfiles={} profiles={}",
                    kind, generation, stale, stored.size());
        }
    }

    public boolean exists(String kind, long generation) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recommendation_eligibility WHERE source_kind = ? AND generation_id = ?",
                Long.class, kind, generation);
        return count != null && count > 0;
    }

    /** 같은 적용 실행을 다시 받았을 때 저장된 근거와 같은지 확인한다. 근거가 없는 요청은 저장된 근거도 없어야 같다. */
    public boolean same(String kind, long generation, JsonNode proof) {
        if (proof == null) {
            return !exists(kind, generation);
        }
        List<String> stored = jdbc.queryForList(
                "SELECT CAST(proof AS CHAR) FROM recommendation_eligibility WHERE source_kind = ? AND generation_id = ?",
                String.class, kind, generation);
        return stored.size() == 1 && parse(stored.getFirst()).equals(parse(proof.toString()));
    }

    /** 지금 공개해도 되는 추천만 골라 돌려준다. 근거가 없거나 버려진 세대, 소개가 바뀐 후보는 빠진다. */
    public Set<Ref> valid(String kind, List<Ref> refs) {
        if (refs.isEmpty()) {
            return Set.of();
        }
        Map<Long, Map<Long, Profile>> profilesByGeneration = loadProfiles(kind,
                refs.stream().map(Ref::generation).collect(Collectors.toSet()));
        Set<Long> creatorIds = new HashSet<>();
        for (Ref ref : refs) {
            creatorIds.add(ref.candidate());
            if (ref.seed() != null) {
                creatorIds.add(ref.seed());
            }
        }
        Map<Long, String> current = currentHashes(creatorIds);
        Set<Ref> result = new HashSet<>();
        for (Ref ref : refs) {
            if (matches(ref, profilesByGeneration.getOrDefault(ref.generation(), Map.of()), current)) {
                result.add(ref);
            }
        }
        return result;
    }

    /** 보관한 것은 그대로 쓰고 없는 세대만 한 번에 읽어 파싱한다. 근거 행이 없는 구 세대는 보관하지 않는다. */
    private Map<Long, Map<Long, Profile>> loadProfiles(String kind, Set<Long> generations) {
        Map<Long, Map<Long, Profile>> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (long generation : generations) {
            Optional<Map<Long, Profile>> cached = parsedProofs.getIfPresent(cacheKey(kind, generation));
            if (cached == null) {
                missing.add(generation);
            } else {
                cached.ifPresent(profiles -> result.put(generation, profiles));
            }
        }
        if (missing.isEmpty()) {
            return result;
        }
        // 같은 분류체계를 쓰는 세대가 많아도 분야 코드는 한 번만 읽는다.
        Map<String, Set<String>> taxonomies = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query(
                "SELECT generation_id, CAST(proof AS CHAR) FROM recommendation_eligibility "
                        + "WHERE source_kind = :kind AND generation_id IN (:ids)",
                Map.of("kind", kind, "ids", missing),
                (RowCallbackHandler) row -> {
                    long generation = row.getLong(1);
                    Map<Long, Profile> profiles = parseForQuery(kind, generation, row.getString(2), taxonomies);
                    parsedProofs.put(cacheKey(kind, generation), Optional.ofNullable(profiles));
                    if (profiles != null) {
                        result.put(generation, profiles);
                    }
                });
        return result;
    }

    /** 조회용 파싱이다. 버릴 근거는 세대마다 한 번(보관이 만료되면 다시) 이유와 함께 경고하고 null을 돌려준다. */
    private Map<Long, Profile> parseForQuery(
            String kind, long generation, String raw, Map<String, Set<String>> taxonomies) {
        String reason = "MALFORMED";
        try {
            JsonNode proof = parse(raw);
            reason = "HEADER";
            requireHeader(proof);
            reason = "TAXONOMY";
            Set<String> allowed = taxonomy(proof, false, taxonomies);
            reason = "PROFILES";
            return profiles(proof, allowed);
        } catch (BusinessException e) {
            log.warn("RECOMMENDATION_PROOF_REJECTED sourceKind={} generationId={} reason={} visible=false",
                    kind, generation, reason);
            return null;
        }
    }

    /** 근거를 직접 고친 운영 보정이나 테스트 뒤에 보관한 파싱 결과를 비운다. */
    public void clearCache() {
        parsedProofs.invalidateAll();
    }

    private static String cacheKey(String kind, long generation) {
        return kind + ":" + generation;
    }

    private static JsonNode parse(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static void requireHeader(JsonNode proof) {
        if (proof == null || !proof.isObject() || proof.size() != 4
                || !"RELATED_V1".equals(proof.path("policyVersion").asText())
                || !proof.path("taxonomyVersion").isTextual()
                || !proof.path("taxonomyHash").asText().matches(HASH_PATTERN)
                || !proof.path("profiles").isArray()
                || proof.path("profiles").size() > MAX_PROFILES) {
            throw invalid();
        }
    }

    /** 적재는 활성 분류체계만 받고, 조회는 근거에 적힌 등록 버전으로 판정해 분류체계 교체·분야 비활성 뒤에도 기존 추천을 유지한다. */
    private Set<String> taxonomy(JsonNode proof, boolean active, Map<String, Set<String>> cache) {
        requireHeader(proof);
        String version = proof.path("taxonomyVersion").asText();
        String hash = proof.path("taxonomyHash").asText();
        String key = version + ":" + hash;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Set<String> allowed = active ? taxonomyCodes.activeCodes(version, hash) : taxonomyCodes.registeredCodes(version, hash);
        if (allowed.isEmpty()) {
            throw invalid();
        }
        cache.put(key, allowed);
        return allowed;
    }

    private Map<Long, Profile> profiles(JsonNode proof, Set<String> allowedTags) {
        Map<Long, Profile> result = new TreeMap<>();
        long previous = 0;
        for (JsonNode row : proof.path("profiles")) {
            JsonNode creatorId = row.path("creatorId");
            if (!row.isObject() || row.size() != 3 || !creatorId.isIntegralNumber() || !creatorId.canConvertToLong()
                    || creatorId.asLong() <= previous
                    || !row.path("introductionHash").asText().matches(HASH_PATTERN)
                    || !row.path("tags").isArray() || row.path("tags").size() > MAX_TAGS) {
                throw invalid();
            }
            Set<String> tags = new HashSet<>();
            String last = "";
            for (JsonNode tag : row.path("tags")) {
                // 태그는 중복 없이 오름차순이어야 해서 같은 근거는 항상 같은 JSON이 된다.
                if (!tag.isTextual() || !allowedTags.contains(tag.asText()) || last.compareTo(tag.asText()) >= 0) {
                    throw invalid();
                }
                last = tag.asText();
                tags.add(last);
            }
            previous = creatorId.asLong();
            result.put(previous, new Profile(row.path("introductionHash").asText(), tags));
        }
        return result;
    }

    private Map<Long, String> currentHashes(Set<Long> creatorIds) {
        Map<Long, String> hashes = new HashMap<>();
        introductions.findIntroTexts(creatorIds)
                .forEach((creatorId, text) -> hashes.put(creatorId, introductionHash(text)));
        return hashes;
    }

    /** 후보(유사 추천이면 seed까지)의 소개가 근거를 만들 때와 같고 비어 있지 않으며, 분야·공통 태그 조건을 만족하는지 본다. */
    private static boolean matches(Ref ref, Map<Long, Profile> profiles, Map<Long, String> currentHashes) {
        Profile candidate = profiles.get(ref.candidate());
        if (!isCurrent(candidate, currentHashes.get(ref.candidate()))) {
            return false;
        }
        if (candidate.tags().isEmpty()
                || (!ref.interests().isEmpty() && Collections.disjoint(candidate.tags(), ref.interests()))) {
            return false;
        }
        if (ref.seed() == null) {
            return true;
        }
        Profile seed = profiles.get(ref.seed());
        return isCurrent(seed, currentHashes.get(ref.seed())) && !Collections.disjoint(seed.tags(), candidate.tags());
    }

    private static boolean isCurrent(Profile profile, String currentHash) {
        return profile != null && !EMPTY_HASH.equals(currentHash) && profile.introductionHash().equals(currentHash);
    }
}
