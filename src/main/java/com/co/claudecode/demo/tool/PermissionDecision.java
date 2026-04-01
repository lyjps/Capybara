package com.co.claudecode.demo.tool;

public record PermissionDecision(boolean allowed, String reason) {

    public static PermissionDecision allow() {
        return new PermissionDecision(true, "allowed");
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }
}
