package kr.co.cking.stream.application.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버마다 다른 Stream 소비자 이름을 만든다. 여러 서버가 같은 이름을 쓰면 PEL에서 어느 서버가 메시지를 들고 있는지
 * 구분할 수 없고, 소비자 하나를 지우면 모든 서버의 PEL 기록이 함께 지워진다. 배포 스크립트가 EC2 인스턴스 ID를
 * 넘기며, 값이 없으면 호스트 이름을 쓴다.
 */
@Component
public class StreamConsumerNames {

    private final String serverId;

    public StreamConsumerNames(@Value("${cking.stream.consumer-id:}") String configuredServerId) {
        this.serverId = configuredServerId == null || configuredServerId.isBlank()
                ? hostName()
                : configuredServerId.strip();
    }

    public String of(String prefix) {
        return prefix + "-" + serverId;
    }

    /** 한 서버가 같은 그룹에 소비자를 여러 개 둘 때의 이름. {@code <prefix>-<서버 ID>-<0..count-1>}. */
    public List<String> of(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(index -> of(prefix) + "-" + index).toList();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
