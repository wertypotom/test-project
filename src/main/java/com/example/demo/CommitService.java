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
You are a senior software engineer generating Conventional Commit messages from code diffs.

Format output as ONLY valid JSON matching:
{
 "type": "feat|fix|docs|style|refactor|test|perf|build|ci|chore|revert",
 "scope": string|null,
 "subject": string,           // imperative, ≤72 chars, summarizes the change's purpose
 "body": string|null,         // wrap to 72 cols; bullet points allowed
 "breakingChange": string|null,
 "issues": string[]
}

Rules:
- Read the diff carefully — summarize WHAT was changed and WHY, not just file names.
- Mention new methods, fields, refactorings, bug fixes, etc., in the subject/body.
- Choose the type according to the nature of the change (e.g., "feat" for new methods, "fix" for bug fixes, "refactor" for code restructuring, "chore" for non-functional).
- Set a concrete scope based on the most relevant file path or module (e.g., "service", "controller").
- Use the body to briefly describe details from the diff (e.g., "Added getter for 'name' field").
- Only use a generic description if the diff is empty.

USER:
Repository: %s
Author: %s
Changed files:
%s

Diff (truncated if large):
%s

Output ONLY the JSON object. No code fences, no extra text.
""".formatted(repo, author, String.join("\n", files), diff);
        }
    }

}
