package dev.sumituppal.pager.hitl;

public interface NotificationSink {
    String channel();
    void send(String triageId, String message);
}