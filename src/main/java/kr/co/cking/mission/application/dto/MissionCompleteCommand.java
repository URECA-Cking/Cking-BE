package kr.co.cking.mission.application.dto;

import java.util.UUID;

/** Controller가 {@code MissionCompleteRequest}를 변환해 전달하는 커맨드. */
public record MissionCompleteCommand(Long userId, UUID requestId) {
}
