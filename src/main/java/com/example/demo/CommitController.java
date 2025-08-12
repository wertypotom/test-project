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
    PrDescriptionService prDescriptionService;

    private final CommitService service;
    private final ChatClient chat;

    public CommitController(CommitService service, ChatClient chat) {
        this.service = service;
        this.chat = chat;
    }

    record GenerateRequest(String repo, String author, List<String> files, String diff) {}
    record GenerateResponse(String message, CommitMessage structured) {}

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

    @GetMapping(path = "/ping-openai", produces = MediaType.TEXT_PLAIN_VALUE)
    public String pingOpenAI() {
        try { return chat.prompt("Say OK").call().content(); }
        catch (Exception e) { e.printStackTrace(); return "PING ERROR: " + e.getMessage(); }
    }

    // Debug endpoint that returns the raw model output for troubleshooting
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

        // Reuse the service's prompt builder (same path as real generation)
        String raw = chat.prompt(CommitService.PromptBuilder.build(repo, author, files,
                diff.length() > 18_000 ? diff.substring(0, 18_000) : diff)).call().content();
        return raw;
    }

    @PostMapping(path = "/generate-commit",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public GenerateResponse generateJson(@RequestBody GenerateRequest req) {
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
        var files = (filesRaw == null || filesRaw.isBlank())
                ? List.<String>of()
                : Arrays.stream(filesRaw.split("\\R|,"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        var cm = service.generate(repo, author, files, diff);
        return service.format(cm);
    }
}
