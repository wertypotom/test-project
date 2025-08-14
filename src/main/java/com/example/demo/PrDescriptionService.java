package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PrDescriptionService {

    private final ChatClient chat; // can be null

    public PrDescriptionService(@Autowired(required = false) ChatClient chat) {
        this.chat = chat;
    }

    public String buildDeterministic(List<CommitMessage> commits) {
        if (commits == null || commits.isEmpty()) return "# Update\n\n_No commits found._\n";

        String title = commits.stream()
                .filter(c -> {
                    var t = c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT);
                    return List.of("feat","fix","perf","refactor").contains(t);
                })
                .map(CommitMessage::subject)
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank())
                .findFirst()
                .orElseGet(() -> commits.stream()
                        .map(CommitMessage::subject).filter(Objects::nonNull).map(String::trim)
                        .filter(s -> !s.isBlank()).findFirst().orElse("Update"));
        title = stripWrappingQuotes(title);

        var byType = commits.stream()
                .collect(Collectors.groupingBy(
                        c -> Optional.ofNullable(c.type()).orElse("other").toLowerCase(Locale.ROOT),
                        LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        sb.append("## Summary\n");
        sb.append("- This PR includes ").append(commits.size()).append(" commit")
                .append(commits.size()==1? "" : "s").append(".\n\n");

        sb.append("## Changes\n");
        List<String> typeOrder = List.of("feat","fix","perf","refactor","docs","test","build","ci","chore","style","revert","other");
        for (String t : typeOrder) {
            var list = byType.getOrDefault(t, List.of());
            if (list.isEmpty()) continue;
            sb.append("### ").append(t).append("\n");
            for (var c : list) {
                var scope = (c.scope()==null || c.scope().isBlank()) ? "" : "(" + c.scope().trim() + ")";
                var subject = Optional.ofNullable(c.subject()).orElse("").trim();
                boolean breaking = c.breakingChange()!=null && !c.breakingChange().isBlank();

                // Header line for this commit
                sb.append("- ").append(t).append(scope);
                if (breaking) sb.append("!");
                sb.append(": ").append(subject).append("\n");

                // BODY → bullets (preserve existing "- " bullets, otherwise add our own)
                if (c.body()!=null && !c.body().isBlank()) {
                    for (String line : c.body().strip().split("\\R+")) {
                        var tline = line.trim();
                        if (tline.isBlank()) continue;
                        sb.append(tline.startsWith("- ") ? "  " + tline : "  - " + tline).append("\n");
                    }
                }

                // BREAKING (if any)
                if (breaking) {
                    sb.append("  - BREAKING: ").append(c.breakingChange().trim()).append("\n");
                }

                // Issues (if any)
                if (c.issues()!=null && !c.issues().isEmpty()) {
                    sb.append("  - Issues: ").append(String.join(" ", c.issues())).append("\n");
                }
            }
            sb.append("\n");
        }

        var breaking = commits.stream()
                .map(CommitMessage::breakingChange)
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (!breaking.isEmpty()) {
            sb.append("## ⚠️ Breaking Changes\n");
            breaking.forEach(b -> sb.append("- ").append(b).append("\n"));
            sb.append("\n");
        }

        var issues = commits.stream()
                .flatMap(c -> c.issues()==null? Stream.<String>empty() : c.issues().stream())
                .distinct().toList();
        if (!issues.isEmpty()) {
            sb.append("## Related Issues\n").append(String.join(" ", issues)).append("\n\n");
        }

        sb.append("_Generated from Conventional Commit messages._\n");
        return sb.toString();
    }

    public String polishWithAi(String markdown) {
        if (chat == null) return markdown;
        try {
            String prompt = """
        Improve clarity and tone of the following PR description, but keep the same sections and bullets.
        Do not add extra sections. Keep the title succinct.

        ---
        %s
        ---
        """.formatted(markdown);
            return chat.prompt(prompt).call().content();
        } catch (Exception e) {
            return markdown;
        }
    }

    // --------- Parser for raw git log (no jq needed in CI) ----------

    // Matches header: type(scope?): subject
    private static final Pattern HEADER = Pattern.compile("^([a-z]+)(?:\\(([^)]+)\\))?!?:\\s*(.+)$");
    private static final Pattern BREAKING = Pattern.compile("^BREAKING CHANGE:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE = Pattern.compile("(?:(?:#\\d+)|(?:[A-Z]+-\\d+))");

    /** Parse commits from a block of messages separated by a line "----8<----". */
    public List<CommitMessage> parseCommitsFromLog(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] blocks = raw.split("(?m)^----8<----\\s*$");
        List<CommitMessage> out = new ArrayList<>();
        for (String block : blocks) {
            List<String> lines = Arrays.stream(block.strip().split("\\R")).toList();
            if (lines.isEmpty()) continue;

            String type=null, scope=null, subject=null, breaking=null;
            StringBuilder body = new StringBuilder();

            // header = first non-empty line
            for (String line : lines) {
                if (line.isBlank()) continue;
                Matcher m = HEADER.matcher(line.trim());
                if (m.matches()) {
                    type = m.group(1);
                    scope = m.group(2);
                    subject = m.group(3);
                    break;
                } else {
                    // Not conventional? Treat as subject-only
                    subject = line.trim();
                    type = (type==null ? "chore" : type);
                    break;
                }
            }

            // rest = body + trailers
            for (String line : lines) {
                if (line.startsWith("#")) continue; // skip comments
                Matcher br = BREAKING.matcher(line);
                if (br.find()) breaking = br.group(1).trim();
                else body.append(line).append("\n");
            }

            // issues
            Set<String> issues = new LinkedHashSet<>();
            Matcher im = ISSUE.matcher(body.toString());
            while (im.find()) issues.add(im.group());

            String bodyStr = body.toString().strip();
            out.add(new CommitMessage(
                    type == null ? "chore" : type,
                    scope,
                    stripWrappingQuotes(subject),
                    bodyStr.isBlank()? null : bodyStr,
                    breaking,
                    new ArrayList<>(issues))
            );
        }
        return out;
    }

    // ---------- small helpers ----------

    /** Strip a single pair of wrapping single or double quotes. */
    private static String stripWrappingQuotes(String s) {
        if (s == null) return null;
        return s.replaceAll("^(\"|')(.+)(\\1)$", "$2");
    }
}
