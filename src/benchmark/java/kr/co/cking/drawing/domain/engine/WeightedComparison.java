package kr.co.cking.drawing.domain.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;

/** 개발 전용 비교 구현. 운영 풀에 접근하기 위해 같은 패키지의 별도 source set에 둔다. */
final class WeightedComparison {

    enum Method { TICKET_ARRAY, PREFIX_LINEAR, PREFIX_BINARY, FENWICK }

    record Limits(long maxTickets, long maxTicketBytes) {
        static final Limits DEFAULT = new Limits(1_000_000, 8 * 1024 * 1024);

        Limits {
            if (maxTickets <= 0 || maxTicketBytes <= 0) {
                throw new IllegalArgumentException("티켓 상한은 양수여야 합니다.");
            }
        }

        String exclusion(long total) {
            if (total > maxTickets) {
                return "MAX_TICKETS: " + total + " > " + maxTickets;
            }
            if (total > Integer.MAX_VALUE - 8L) {
                return "JAVA_ARRAY_LIMIT: " + total;
            }
            // int[] 헤더를 보수적으로 24바이트로 잡고 8바이트 정렬을 반영한다.
            long estimatedBytes = (24 + total * Integer.BYTES + 7) & ~7L;
            return estimatedBytes > maxTicketBytes
                    ? "MAX_TICKET_BYTES: " + estimatedBytes + " > " + maxTicketBytes : null;
        }
    }

    record Prepared(List<CandidateValue> candidates, DrawingSeed seed, int winnerCount,
                    long totalWeight) { }

    interface Pool {
        long totalWeight();
        CandidateValue selectAndRemove(long selectedWeight);
    }

    static Prepared normalize(List<CandidateValue> raw, Set<Long> excluded,
                              DrawingSeed seed, int winnerCount) {
        // 운영 DrawInput의 검증/정렬을 그대로 사용한다. 제외는 엔진과 같은 시점에 적용한다.
        DrawInput input = new DrawInput(1L, 1L, "0".repeat(64), seed, "WEIGHTED_V1",
                winnerCount, raw, excluded);
        List<CandidateValue> eligible = input.candidates().stream()
                .filter(candidate -> !input.excludedMemberIds().contains(candidate.memberId()))
                .toList();
        if (winnerCount > eligible.size()) {
            throw new IllegalArgumentException("제외 대상을 반영한 후보 수보다 당첨자 수가 큽니다.");
        }
        long total = 0;
        try {
            for (CandidateValue candidate : eligible) {
                total = Math.addExact(total, candidate.ticketCount());
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("가중치 합계가 long 범위를 초과했습니다.", exception);
        }
        return new Prepared(eligible, seed, winnerCount, total);
    }

    static Pool build(Method method, Prepared input, Limits limits) {
        return switch (method) {
            case TICKET_ARRAY -> new TicketPool(input, limits);
            case PREFIX_LINEAR -> new PrefixPool(input, false);
            case PREFIX_BINARY -> new PrefixPool(input, true);
            case FENWICK -> new Pool() {
                private final WeightedCandidatePool delegate = new WeightedCandidatePool(input.candidates());

                public long totalWeight() { return delegate.totalWeight(); }

                public CandidateValue selectAndRemove(long weight) {
                    return delegate.selectAndRemove(weight);
                }
            };
        };
    }

    static DrawOutput select(Prepared input, Pool pool) {
        DeterministicRandom random = new DeterministicRandom(input.seed());
        List<DrawWinner> winners = new ArrayList<>(input.winnerCount());
        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            // 모든 방식에서 당첨 순위마다 동일한 bound로 정확히 한 번 호출한다.
            CandidateValue winner = pool.selectAndRemove(random.nextLong(pool.totalWeight()));
            winners.add(new DrawWinner(winner.memberId(), rank, winner.ticketCount()));
        }
        return new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, winners);
    }

    private static void validateWeight(long selected, long total) {
        if (selected < 0 || selected >= total) {
            throw new IllegalArgumentException("선택 가중치가 현재 합계 범위를 벗어났습니다.");
        }
    }

    private static final class TicketPool implements Pool {
        private final List<CandidateValue> candidates;
        private final int[] tickets;
        private int size;

        TicketPool(Prepared input, Limits limits) {
            String exclusion = limits.exclusion(input.totalWeight());
            if (exclusion != null) {
                throw new IllegalArgumentException(exclusion);
            }
            candidates = input.candidates();
            tickets = new int[(int) input.totalWeight()];
            for (int index = 0; index < candidates.size(); index++) {
                int end = Math.addExact(size, (int) candidates.get(index).ticketCount());
                Arrays.fill(tickets, size, end, index);
                size = end;
            }
        }

        public long totalWeight() { return size; }

        public CandidateValue selectAndRemove(long selectedWeight) {
            validateWeight(selectedWeight, size);
            int winnerIndex = tickets[(int) selectedWeight];
            int remaining = 0;
            // 안정적 압축으로 해당 회원의 모든 티켓을 제거하고 누적 구간 순서를 보존한다.
            for (int index = 0; index < size; index++) {
                if (tickets[index] != winnerIndex) {
                    tickets[remaining++] = tickets[index];
                }
            }
            size = remaining;
            return candidates.get(winnerIndex);
        }
    }

    private static final class PrefixPool implements Pool {
        private final List<CandidateValue> candidates;
        private final long[] weights;
        private final long[] prefix;
        private final boolean binary;

        PrefixPool(Prepared input, boolean binary) {
            this.candidates = input.candidates();
            this.binary = binary;
            weights = new long[candidates.size()];
            prefix = new long[candidates.size()];
            long total = 0;
            for (int index = 0; index < candidates.size(); index++) {
                weights[index] = candidates.get(index).ticketCount();
                total = Math.addExact(total, weights[index]);
                prefix[index] = total;
            }
        }

        public long totalWeight() { return prefix[prefix.length - 1]; }

        public CandidateValue selectAndRemove(long selectedWeight) {
            validateWeight(selectedWeight, totalWeight());
            int selected = binary ? upperBound(selectedWeight) : linearSearch(selectedWeight);
            long removed = weights[selected];
            weights[selected] = 0;
            // 탐색만 측정하지 않도록 매 당첨 후 누적합 갱신 비용도 선정 단계에 포함한다.
            for (int index = selected; index < prefix.length; index++) {
                prefix[index] -= removed;
            }
            return candidates.get(selected);
        }

        private int linearSearch(long selectedWeight) {
            int index = 0;
            while (prefix[index] <= selectedWeight) {
                index++;
            }
            return index;
        }

        private int upperBound(long selectedWeight) {
            int low = 0;
            int high = prefix.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (prefix[middle] <= selectedWeight) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }
}
