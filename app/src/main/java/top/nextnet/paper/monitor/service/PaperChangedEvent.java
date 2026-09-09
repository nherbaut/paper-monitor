package top.nextnet.paper.monitor.service;

public record PaperChangedEvent(Long logicalFeedId, String type) {}
