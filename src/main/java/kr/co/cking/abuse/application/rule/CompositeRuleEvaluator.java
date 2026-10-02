package kr.co.cking.abuse.application.rule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.DetectionEvidence.Scope;
import kr.co.cking.abuse.domain.DetectionEvidence.SupportingEvidence;
import org.springframework.stereotype.Component;

/** 같은 Observation에서 평가한 기본 Rule·Signal 후보를 Composite 근거로 보강한다. */
@Component
public class CompositeRuleEvaluator {

    private final AbuseProperties properties;

    public CompositeRuleEvaluator(AbuseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
    }

    /** 모든 후보는 동일 Observation과 Feature Snapshot으로 계산된 결과여야 한다. */
    public List<AbuseRuleMatch> enrich(
            AbuseObservationEvent observation,
            AbuseFeatureSnapshot snapshot,
            RequestBurstRuleEvaluation requestEvaluation,
            List<AbuseRuleMatch> failureMatches,
            List<AbuseRuleMatch> rapidMatches
    ) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
        Objects.requireNonNull(requestEvaluation, "requestEvaluation은 필수입니다.");
        Objects.requireNonNull(failureMatches, "failureMatches는 필수입니다.");
        Objects.requireNonNull(rapidMatches, "rapidMatches는 필수입니다.");
        if (!properties.enabled()) {
            return List.of();
        }
        properties.windowPolicy();

        List<AbuseRuleMatch> matches = new ArrayList<>();
        matches.addAll(requestEvaluation.matches());
        matches.addAll(failureMatches);
        matches.addAll(rapidMatches);
        EnumMap<AbuseType, AbuseRuleMatch> byType = new EnumMap<>(AbuseType.class);
        for (AbuseRuleMatch match : matches) {
            requireCurrentScope(observation, match);
            if (byType.putIfAbsent(match.abuseType(), match) != null) {
                throw new IllegalArgumentException("같은 Observation에 동일 AbuseType 후보가 중복됐습니다.");
            }
        }

        DetectionEvidence rotation = requestEvaluation.signalEvidence().get(AbuseSignal.REQUEST_ID_ROTATION);
        if (rotation != null) {
            requireRotationScope(observation, rotation);
        }

        EnumMap<AbuseType, Changes> changes = new EnumMap<>(AbuseType.class);
        if (observation.actionType() == AbuseActionType.MISSION_COMPLETE) {
            enrichMission(byType, rotation, changes);
        } else {
            enrichEntry(snapshot, byType, changes);
        }
        AbuseRuleMatch failure = byType.get(AbuseType.FAILURE_BURST);
        if (failure != null && snapshot.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)
                >= properties.failureBurst().distinctTypeThreshold()) {
            changes.computeIfAbsent(AbuseType.FAILURE_BURST, ignored -> new Changes())
                    .add(AbuseCompositeRule.RULE_05);
        }

        return matches.stream().map(match -> apply(match, changes.get(match.abuseType()))).toList();
    }

    private void enrichMission(
            Map<AbuseType, AbuseRuleMatch> matches,
            DetectionEvidence rotation,
            Map<AbuseType, Changes> changes
    ) {
        if (rotation == null) {
            return;
        }
        SupportingEvidence support = signalSupport(AbuseSignal.REQUEST_ID_ROTATION, rotation);
        if (matches.containsKey(AbuseType.DUPLICATE_MISSION_BURST)) {
            changes.computeIfAbsent(AbuseType.DUPLICATE_MISSION_BURST, ignored -> new Changes())
                    .add(AbuseCompositeRule.RULE_01, support);
        }
        if (matches.containsKey(AbuseType.MISSION_REQUEST_BURST)) {
            changes.computeIfAbsent(AbuseType.MISSION_REQUEST_BURST, ignored -> new Changes())
                    .add(AbuseCompositeRule.RULE_02, support);
        }
    }

    private void enrichEntry(
            AbuseFeatureSnapshot snapshot,
            Map<AbuseType, AbuseRuleMatch> matches,
            Map<AbuseType, Changes> changes
    ) {
        AbuseRuleMatch entry = matches.get(AbuseType.ENTRY_REQUEST_BURST);
        AbuseRuleMatch insufficient = matches.get(AbuseType.INSUFFICIENT_BALANCE_BURST);
        AbuseRuleMatch failure = matches.get(AbuseType.FAILURE_BURST);
        AbuseRuleMatch rapid = matches.get(AbuseType.RAPID_EARN_AND_SPEND);

        if (entry != null) {
            for (AbuseRuleMatch companion : Stream.of(insufficient, failure).filter(Objects::nonNull).toList()) {
                changes.computeIfAbsent(entry.abuseType(), ignored -> new Changes())
                        .add(AbuseCompositeRule.RULE_03, matchSupport(companion));
                changes.computeIfAbsent(companion.abuseType(), ignored -> new Changes())
                        .add(AbuseCompositeRule.RULE_03, matchSupport(entry));
            }
        }

        if (rapid == null) {
            return;
        }
        boolean recentMissionBurst = snapshot.values().containsKey(AbuseMetric.MISSION_REQUEST_COUNT)
                && snapshot.valueOf(AbuseMetric.MISSION_REQUEST_COUNT)
                        >= properties.missionRequestBurst().threshold();
        if (entry == null && !recentMissionBurst) {
            return;
        }
        Changes rapidChanges = changes.computeIfAbsent(rapid.abuseType(), ignored -> new Changes());
        if (entry != null) {
            rapidChanges.add(AbuseCompositeRule.RULE_04, matchSupport(entry));
            changes.computeIfAbsent(entry.abuseType(), ignored -> new Changes())
                    .add(AbuseCompositeRule.RULE_04, matchSupport(rapid));
        }
        if (recentMissionBurst) {
            AbuseMetric metric = AbuseMetric.MISSION_REQUEST_COUNT;
            rapidChanges.add(AbuseCompositeRule.RULE_04, new SupportingEvidence(
                    AbuseType.MISSION_REQUEST_BURST, null,
                    new Scope(Scope.Type.USER, null, null, null, null, null),
                    new DetectionEvidence.Window(properties.missionRequestBurst().window().toMillis(), null),
                    Map.of(metric, snapshot.valueOf(metric)),
                    Map.of(metric, properties.missionRequestBurst().threshold().longValue())));
        }
    }

    private void requireCurrentScope(AbuseObservationEvent observation, AbuseRuleMatch match) {
        Scope scope = match.evidence().scope();
        boolean valid = switch (match.abuseType()) {
            case MISSION_REQUEST_BURST -> observation.actionType() == AbuseActionType.MISSION_COMPLETE
                    && scope.type() == Scope.Type.USER;
            case DUPLICATE_MISSION_BURST -> observation.actionType() == AbuseActionType.MISSION_COMPLETE
                    && matchesBusinessKey(observation, scope);
            case ENTRY_REQUEST_BURST -> observation.actionType() == AbuseActionType.EVENT_ENTRY
                    && scope.type() == Scope.Type.USER_EVENT && Objects.equals(scope.eventId(), observation.eventId());
            case INSUFFICIENT_BALANCE_BURST, RAPID_EARN_AND_SPEND ->
                    observation.actionType() == AbuseActionType.EVENT_ENTRY
                            && scope.type() == Scope.Type.USER_BALANCE_SCOPE
                            && Objects.equals(scope.balanceScope(), observation.balanceScope());
            case FAILURE_BURST -> scope.type() == Scope.Type.USER;
        };
        if (!valid) {
            throw new IllegalArgumentException("Observation과 다른 범위의 Rule 후보를 조합할 수 없습니다.");
        }
    }

    private void requireRotationScope(AbuseObservationEvent observation, DetectionEvidence evidence) {
        if (observation.actionType() != AbuseActionType.MISSION_COMPLETE
                || !evidence.signals().contains(AbuseSignal.REQUEST_ID_ROTATION)
                || !matchesBusinessKey(observation, evidence.scope())) {
            throw new IllegalArgumentException("현재 Mission Business Key와 다른 Rotation 근거입니다.");
        }
    }

    private boolean matchesBusinessKey(AbuseObservationEvent observation, Scope scope) {
        return scope.type() == Scope.Type.BUSINESS_KEY
                && Objects.equals(scope.creatorId(), observation.creatorId())
                && Objects.equals(scope.missionId(), observation.missionId())
                && Objects.equals(scope.periodKey(), observation.periodKey())
                && Objects.equals(scope.balanceScope(), observation.balanceScope());
    }

    private SupportingEvidence signalSupport(AbuseSignal signal, DetectionEvidence evidence) {
        return new SupportingEvidence(null, signal, evidence.scope(), evidence.window(),
                evidence.features(), evidence.thresholds());
    }

    private SupportingEvidence matchSupport(AbuseRuleMatch match) {
        DetectionEvidence evidence = match.evidence();
        return new SupportingEvidence(match.abuseType(), null, evidence.scope(), evidence.window(),
                evidence.features(), evidence.thresholds());
    }

    private AbuseRuleMatch apply(AbuseRuleMatch match, Changes changes) {
        if (changes == null) {
            return match;
        }
        DetectionEvidence evidence = match.evidence();
        EnumSet<AbuseCompositeRule> rules = EnumSet.noneOf(AbuseCompositeRule.class);
        rules.addAll(evidence.matchedRules());
        rules.addAll(changes.rules);
        EnumSet<AbuseSignal> signals = EnumSet.noneOf(AbuseSignal.class);
        signals.addAll(evidence.signals());
        signals.addAll(changes.signals);
        EnumMap<AbuseCompositeRule, List<SupportingEvidence>> supports = new EnumMap<>(AbuseCompositeRule.class);
        supports.putAll(evidence.supportingEvidence());
        changes.supports.forEach((rule, values) -> {
            List<SupportingEvidence> merged = new ArrayList<>(supports.getOrDefault(rule, List.of()));
            merged.addAll(values);
            supports.put(rule, List.copyOf(merged));
        });
        return new AbuseRuleMatch(match.abuseType(), new DetectionEvidence(
                evidence.policyVersion(), evidence.scope(), evidence.window(),
                evidence.features(), evidence.thresholds(), signals, rules, supports));
    }

    private static final class Changes {
        private final EnumSet<AbuseCompositeRule> rules = EnumSet.noneOf(AbuseCompositeRule.class);
        private final EnumSet<AbuseSignal> signals = EnumSet.noneOf(AbuseSignal.class);
        private final EnumMap<AbuseCompositeRule, List<SupportingEvidence>> supports =
                new EnumMap<>(AbuseCompositeRule.class);

        private void add(AbuseCompositeRule rule) {
            rules.add(rule);
        }

        private void add(AbuseCompositeRule rule, SupportingEvidence support) {
            rules.add(rule);
            if (support.signal() != null) {
                signals.add(support.signal());
            }
            List<SupportingEvidence> values = new ArrayList<>(supports.getOrDefault(rule, List.of()));
            if (!values.contains(support)) {
                values.add(support);
            }
            supports.put(rule, List.copyOf(values));
        }
    }
}
