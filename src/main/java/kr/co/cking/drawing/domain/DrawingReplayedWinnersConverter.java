package kr.co.cking.drawing.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/** 재현 당첨자 목록과 이력의 JSON 컬럼을 상호 변환한다. */
@Converter
public class DrawingReplayedWinnersConverter implements AttributeConverter<List<DrawingReplayedWinner>, String> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<DrawingReplayedWinner>> WINNERS_TYPE = new TypeReference<>() {
    };

    /** 재현에 도달하지 않은 null과 빈 결과 목록을 구분해 JSON으로 저장한다. */
    @Override
    public String convertToDatabaseColumn(List<DrawingReplayedWinner> winners) {
        if (winners == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(winners);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("재현 당첨자를 JSON으로 저장할 수 없습니다.", exception);
        }
    }

    /** 기존 이력의 null·공백·JSON null을 처리하고 저장된 재현 당첨자 목록을 복원한다. */
    @Override
    public List<DrawingReplayedWinner> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<DrawingReplayedWinner> winners = JSON.readValue(json, WINNERS_TYPE);
            return winners == null ? null : List.copyOf(winners);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("재현 당첨자 JSON을 복원할 수 없습니다.", exception);
        }
    }
}
