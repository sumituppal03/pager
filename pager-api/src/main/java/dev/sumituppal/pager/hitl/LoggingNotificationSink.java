package dev.sumituppal.pager.hitl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
@Component
public class LoggingNotificationSink implements NotificationSink {

    private static final Logger log =
        LoggerFactory.getLogger("dev.sumituppal.pager.hitl.notifications");

    @Override
    public String channel() {
        return "log";
    }

    @Override
    public void send(String triageId, String message) {
        // Highly visible pattern — three exclamation marks make this
        // grep-friendly and unambiguously mark an auto-post event.
        log.info("!!! AUTO-POSTED to log sink for triage={} !!!\n{}\n",
            triageId, message);
    }
}