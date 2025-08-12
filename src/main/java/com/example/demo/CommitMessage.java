package com.example.demo;

import java.util.List;

public record CommitMessage(
        String type,
        String scope,
        String subject,
        String body,
        String breakingChange,
        List<String> issues
) {}
