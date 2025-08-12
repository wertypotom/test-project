package com.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class CommitController {

    private final ChatClient chat;
    private final ObjectMapper mapper;

    public CommitController(ChatClient chat) {
        this.chat = chat;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    record GenerateRequest(String repo, String author, List<String> files, String diff) {}
    record GenerateResponse(String message, CommitMessage structured) {}

    @GetMapping(path = "/ping-openai", produces = MediaType.TEXT_PLAIN_VALUE)
    public String pingOpenAI() {
        try {
            return chat.prompt("Say OK").call().content();
        } catch (Exception e) {
            e.printStackTrace();
            return "PING ERROR: " + e.getMessage();
        }
    }

    @PostMapping(path = "/debug-generate-raw",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String debugRaw(@RequestParam(defaultValue = "repo") String repo,
                           @RequestParam(defaultValue = "dev") String author,
                           @RequestParam String diff,
                           @RequestParam(name = "files", defaultValue = "") String filesRaw) {

        var files = (filesRaw == null || filesRaw.isBlank())
                ? List.<String>of()
                : Arrays.stream(filesRaw.split("\\R|,"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        String truncated = (diff == null) ? "" :
                (diff.length() > 18000 ? diff.substring(0, 18000) : diff);

        String prompt = """
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

USER:
Repository: %s
Author: %s
Changed files (staged):
%s

Staged diff (truncated if large):
%s

Output MUST be a single JSON object only (no code fences, no backticks, no extra text).
Produce the JSON now.
""".formatted(
                (repo == null ? "repo" : repo),
                (author == null ? "dev" : author),
                String.join("\n", files),
                truncated
        );

        try {
            // If you're on OpenAI, uncomment for JSON-only responses:
            // var options = OpenAiChatOptions.builder().responseFormat("json_object").build();
            // return chat.prompt(prompt).options(options).call().content();

            return chat.prompt(prompt).call().content();
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR calling model: %s - %s".formatted(e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
        }
    }



    @GetMapping("/health")
    public String health() { return "ok"; }

    @PostMapping(path = "/generate-commit", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public GenerateResponse generateJson(@RequestBody GenerateRequest req) {
        var cm = generateCommit(req.repo(), req.author(), req.files(), req.diff());
        return new GenerateResponse(CommitFormatter.toConventional(cm), cm);
    }

    @PostMapping(path = "/generate-commit-text",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String generateText(@RequestParam(defaultValue = "repo") String repo,
                               @RequestParam(defaultValue = "dev") String author,
                               @RequestParam String diff,
                               @RequestParam(name = "files", defaultValue = "") String filesRaw) {
        var files = filesRaw == null ? List.<String>of()
                : Arrays.stream(filesRaw.split("\\R|,"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
        var cm = generateCommit(repo, author, files, diff);
        return CommitFormatter.toConventional(cm);
    }

    // ----------------- core logic with robust parsing -----------------

    private static final int MAX_DIFF_CHARS = 18000;
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*?\\}", Pattern.DOTALL);

// inside your CommitController

    private CommitMessage generateCommit(String repo, String author, List<String> files, String diff) {
        String truncated = (diff == null) ? "" :
                (diff.length() > 18000 ? diff.substring(0, 18000) : diff);
        System.out.println("💥 before");
        String prompt = """
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

USER:
Repository: %s
Author: %s
Changed files (staged):
%s

Staged diff (truncated if large):
%s

Output MUST be a single JSON object only (no code fences, no backticks, no extra text).
Produce the JSON now.
""".formatted(
                (repo == null ? "repo" : repo),
                (author == null ? "dev" : author),
                String.join("\n", (files == null ? List.of() : files)),
                truncated
        );

        try {
            // (Optional) request JSON-only from OpenAI if you use that provider
            // var options = OpenAiChatOptions.builder().responseFormat("json_object").build();
            // String raw = chat.prompt(prompt).options(options).call().content();

            String raw = chat.prompt(prompt).call().content();

            try {
                return mapper.readValue(raw, CommitMessage.class);
            } catch (Exception parse1) {
                var m = JSON_BLOCK.matcher(raw);
                if (m.find()) {
                    return mapper.readValue(m.group(), CommitMessage.class);
                }
                System.err.println("[commit-ai] could not parse model output, raw was:\n" + raw);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Fallback
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
        // take top-level folder of first file as scope, e.g., "api", "web", "infra"
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
}
