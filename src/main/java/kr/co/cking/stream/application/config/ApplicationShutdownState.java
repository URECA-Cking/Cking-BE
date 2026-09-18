package kr.co.cking.stream.application.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class ApplicationShutdownState implements ApplicationListener<ContextClosedEvent> {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown.set(true);
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }
}
