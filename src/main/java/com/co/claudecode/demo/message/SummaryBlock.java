package com.co.claudecode.demo.message;

public record SummaryBlock(String summary) implements ContentBlock {

    public SummaryBlock {
        summary = summary == null ? "" : summary;
    }

    @Override
    public String renderForModel() {
        return "[summary]\n" + summary;
    }
}
