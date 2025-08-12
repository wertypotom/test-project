package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
public class CommitController {

    @Autowired
    private PrDescriptionService prDescriptionService;

    // Make these OPTIONAL – app can run without them (e.g., in CI)
    @Autowired(required = false)
    private CommitService service;

    @Autowired(required = false)
    private ChatClient chat;

    // --- PR description endpoints (work in CI with no OpenAI key) ---

    @PostMapping(path = "/generate-pr-description",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String generatePrDescription(@RequestBody List<CommitMessage> commits,
                                        @RequestParam(defaultValue="false") boolean polish) {
        String md = prDescriptionService.buildDeterministic(commits);
        return polish ? prDescriptionService.polishWithAi(md) : md;
    }

    @PostMapping(
            path = "/generate-pr-description-from-log",
            consumes = { MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE },
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public String generatePrDescriptionFromLog(@RequestBody String rawLog,
                                               @RequestParam(defaultValue = "false") boolean polish) {
        var commits = prDescriptionService.parseCommitsFromLog(rawLog == null ? "" : rawLog);
        String md = prDescriptionService.buildDeterministic(commits);
        return polish ? prDescriptionService.polishWithAi(md) : md;
    }

    @GetMapping(path = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() { return "ok"; }

    // --- Endpoints below need ChatClient / CommitService; guard them ---

    @GetMapping(path = "/ping-openai", produces = MediaType.TEXT_PLAIN_VALUE)
    public String pingOpenAI() {
        if (chat == null) return "PING DISABLED (no ChatClient)";
        try { return chat.prompt("Say OK").call().content(); }
        catch (Exception e) { e.printStackTrace(); return "PING ERROR: " + e.getMessage(); }
    }

    @PostMapping(path = "/debug-generate-raw",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String debugRaw(@RequestParam(defaultValue = "repo") String repo,
                           @RequestParam(defaultValue = "dev") String author,
                           @RequestParam String diff,
                           @RequestParam(name = "files", defaultValue = "") String filesRaw) {
        if (chat == null) return "DEBUG DISABLED (no ChatClient)";
        var files = (filesRaw == null || filesRaw.isBlank())
                ? List.<String>of()
                : Arrays.stream(filesRaw.split("\\R|,"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        String raw = chat.prompt(CommitService.PromptBuilder.build(
                repo, author, files, diff.length() > 18_000 ? diff.substring(0, 18_000) : diff
        )).call().content();
        return raw;
    }

    @PostMapping(path = "/generate-commit",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public GenerateResponse generateJson(@RequestBody GenerateRequest req) {
        if (service == null) throw new IllegalStateException("Commit generation unavailable (no AI)");
        var cm = service.generate(req.repo(), req.author(), req.files(), req.diff());
        return new GenerateResponse(CommitFormatter.toConventional(cm), cm);
    }

    @PostMapping(path = "/generate-commit-text",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String generateText(@RequestParam(defaultValue = "repo") String repo,
                               @RequestParam(defaultValue = "dev") String author,
                               @RequestParam String diff,
                               @RequestParam(name = "files", defaultValue = "") String filesRaw) {
        if (service == null) return "AI COMMIT DISABLED (no CommitService)";
        var files = (filesRaw == null || filesRaw.isBlank())
                ? List.<String>of()
                : Arrays.stream(filesRaw.split("\\R|,"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        var cm = service.generate(repo, author, files, diff);
        return service.format(cm);
    }

    // Records stay the same
    record GenerateRequest(String repo, String author, List<String> files, String diff) {}
    record GenerateResponse(String message, CommitMessage structured) {}
}
