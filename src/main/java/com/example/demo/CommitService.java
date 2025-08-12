package com.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@ConditionalOnBean(ChatClient.class)
@Service
public class CommitService {

    private static final int MAX_DIFF_CHARS = 18_000;
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*?\\}", Pattern.DOTALL);

    private final ChatClient chat;
    private final ObjectMapper mapper;

    public CommitService(ChatClient chat) {
        this.chat = chat;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public CommitMessage generate(String repo, String author, List<String> files, String diff) {
        String truncated = (diff == null) ? "" :
                (diff.length() > MAX_DIFF_CHARS ? diff.substring(0, MAX_DIFF_CHARS) : diff);

        String prompt = PromptBuilder.build(
                repo == null ? "repo" : repo,
                author == null ? "dev" : author,
                files == null ? List.of() : files,
                truncated
        );

        String raw;
        try {
            // If using OpenAI and you want stricter JSON, you can enable JSON mode:
            // var options = OpenAiChatOptions.builder().responseFormat("json_object").build();
            // raw = chat.prompt(prompt).options(options).call().content();
            raw = chat.prompt(prompt).call().content();
        } catch (Exception e) {
            e.printStackTrace();
            return fallback(files);
        }

        // Try strict parse, then relaxed "first JSON object" parse
        try {
            return mapper.readValue(raw, CommitMessage.class);
        } catch (Exception parse1) {
            var m = JSON_BLOCK.matcher(raw);
            if (m.find()) {
                try {
                    return mapper.readValue(m.group(), CommitMessage.class);
                } catch (Exception ignore) {
                    // fall through
                }
            }
            System.err.println("[commit-ai] parse failed; raw output was:\n" + raw);
            return fallback(files);
        }
    }

    public String format(CommitMessage cm) {
        return CommitFormatter.toConventional(cm);
    }

    // ---------- fallback & small helpers ----------

    private CommitMessage fallback(List<String> files) {
        String scope = deriveScope(files);
        return new CommitMessage(
                "chore",
                scope,
                "update " + (files.isEmpty() ? "repository files" : "implementation"),
                files.isEmpty() ? null : "Modified:\n" + String.join("\n", files),
                null,
                List.of()
        );
    }


    private String deriveScope(List<String> files) {
        if (files == null || files.isEmpty()) return null;
        String f = files.get(0);
        int slash = f.indexOf('/');
        return (slash > 0) ? f.substring(0, slash) : null;
    }

    private String buildSubjectFromFiles(List<String> files) {
        if (files == null || files.isEmpty()) return "update repository files";
        if (files.size() == 1) return "update " + files.get(0);
        return "update " + files.size() + " files";
    }

    private String buildBodyFromFiles(List<String> files) {
        if (files == null || files.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Changes:\n");
        for (String f : files) sb.append("- ").append(f).append("\n");
        return sb.toString();
    }

    static final class PromptBuilder {
        static String build(String repo, String author, List<String> files, String diff) {
            return """
SYSTEM:
You are a senior software engineer creating high-quality Conventional Commit messages.

OUTPUT FORMAT — return only JSON:
{
 "type": "feat|fix|docs|style|refactor|test|perf|build|ci|chore|revert",
 "scope": string|null,
 "subject": string,          // imperative, ≤72 chars, describes what changed & why
 "body": string|null,        // optional; wrap to 72 cols; bullet points allowed
 "breakingChange": string|null,
 "issues": string[]
}

INSTRUCTIONS:
- Read the DIFF carefully — base your summary on WHAT changed and WHY, not just WHERE.
- Use the FILE LIST only to refine the "scope" field.
- Do NOT write generic messages like "update <filename>" unless the change is truly trivial.
- Infer type from the nature of the change (e.g., code fix→"fix", new feature→"feat", config changes→"chore").
- Subject must be imperative: "add X", "fix Y", "refactor Z".
- Prefer technical clarity over marketing language.
- If unsure, guess the developer's intent from code/context.

USER INPUT:
Staged diff (truncated if large):
%s

Changed files:
%s

Repository: %s
Author: %s

Return ONLY the JSON object, no code fences, no explanations.
""".formatted(diff, String.join("\n", files), repo, author);
        }
    }

}
