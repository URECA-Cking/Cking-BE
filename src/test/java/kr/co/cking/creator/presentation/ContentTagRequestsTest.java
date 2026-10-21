package kr.co.cking.creator.presentation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ContentTagRequestsTest {
    final JsonMapper mapper=JsonMapper.builder().build();
    @Test void requestRecordsRetainFractionalAndStringWireTypes() {
        var decimal=mapper.readValue("{\"leaseSeconds\":1.5}",ContentTagRequests.Claim.class).fields();
        assertThat(decimal.get("leaseSeconds")).isInstanceOf(Double.class);
        var text=mapper.readValue("{\"leaseSeconds\":\"900\"}",ContentTagRequests.Claim.class).fields();
        assertThat(text.get("leaseSeconds")).isEqualTo("900");
    }
    @Test void unknownAutomaticMembersCannotBeSilentlyDroppedFromPayloadHash() {
        assertThatThrownBy(()->mapper.readValue("{\"unknown\":1}",ContentTagRequests.Automatic.class)).isInstanceOf(RuntimeException.class);
    }
}
