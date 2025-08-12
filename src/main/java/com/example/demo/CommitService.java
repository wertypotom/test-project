package com.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/*Some commment*/
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
                buildSubjectFromFiles(files),
                buildBodyFromFiles(files),
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

    // ---------- prompt builder (plain string, no template engine) ----------
    static final class PromptBuilder {
        static String build(String repo, String author, List<String> files, String diff) {
            return """
SYSTEM:
You are a senior developer generating Conventional Commits.
Return ONLY valid JSON matching:
{
 "type": "feat|fix|docs|style|refactor|test|perf|build|ci|chore|revert",
 "scope": string|null,
 "subject": string,          // imperative, ≤72 chars
 "body": string|null,        // wrap to 72 cols; bullet points allowed
 "breakingChange": string|null, // description if breaking, else null
 "issues": string[]          // referenced issue IDs like ["#123"], or []
}

Rules:
- Infer type from the diff (tests→"test", perf-sensitive→"perf", ci files→"ci", etc.).
- Prefer a concrete scope (module, package, or folder) if visible from file paths.
- Do not invent issues; include only those explicitly present in input.
- If API/behavior changes require user action, set breakingChange and reflect "!" later.
- Keep subject concise and imperative: "add", "fix", "refactor", not past tense.
- If multiple files change, choose the most representative scope (top-level directory or module name).
- If the change only adds a line with no behavior change, prefer "chore" unless it clearly adds a feature.

USER:
Repository: %s
Author: %s
Changed files (staged):
%s

Staged diff (truncated if large):
%s

Output MUST be a single JSON object only (no code fences, no backticks, no extra text).
Produce the JSON now.
""".formatted(repo, author, String.join("\n", files), diff);
        }
    }
}
