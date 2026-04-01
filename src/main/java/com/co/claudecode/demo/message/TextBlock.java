package com.co.claudecode.demo.message;

public record TextBlock(String text) implements ContentBlock {

    public TextBlock {
        text = text == null ? "" : text;
    }

    @Override
    public String renderForModel() {
        return text;
    }
}
