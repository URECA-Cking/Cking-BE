package kr.co.cking.drawing.simulation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import kr.co.cking.drawing.domain.seed.DrawingSeed;

public final class SimulationSeeds {
    private SimulationSeeds() {
    }

    public static DrawingSeed derive(DrawingSeed master, String scenarioId, long iteration) {
        if (master == null || scenarioId == null || !scenarioId.matches("[a-z0-9-]+")
                || iteration < 0) {
            throw new IllegalArgumentException("유효한 마스터 Seed, 시나리오 ID, 0 이상 반복 번호가 필요합니다.");
        }
        String payload = "CKING_WEIGHTED_SIMULATION_V1\n" + master.value() + "\n"
                + scenarioId + "\n" + iteration + "\n";
        try {
            return DrawingSeed.fromBytes(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
