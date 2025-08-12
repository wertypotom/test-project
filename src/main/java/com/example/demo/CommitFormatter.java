package com.example.demo;

public final class CommitFormatter {
    private CommitFormatter() {}

    public static String toConventional(CommitMessage m) {
        String type = m.type() == null ? "chore" : m.type();
        String scope = (m.scope() == null || m.scope().isBlank()) ? "" : "(" + m.scope().trim() + ")";
        boolean breaking = m.breakingChange() != null && !m.breakingChange().isBlank();
        String bang = breaking ? "!" : "";

        String header = "%s%s%s: %s".formatted(type, scope, bang, m.subject()).trim();

        StringBuilder sb = new StringBuilder(header).append("\n\n");
        if (m.body() != null && !m.body().isBlank()) {
            sb.append(wrap(m.body().trim(), 72)).append("\n\n");
        }
        if (breaking) {
            sb.append("BREAKING CHANGE: ").append(m.breakingChange().trim()).append("\n\n");
        }
        if (m.issues() != null && !m.issues().isEmpty()) {
            sb.append(String.join(" ", m.issues())).append("\n");
        }
        return sb.toString().trim();
    }

    // naive line wrap for demo
    private static String wrap(String text, int width) {
        String[] words = text.replace("\n", " ").split("\\s+");
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (String w : words) {
            if (col + w.length() + 1 > width) {
                out.append("\n");
                col = 0;
            }
            if (col > 0) { out.append(" "); col++; }
            out.append(w);
            col += w.length();
        }
        return out.toString();
    }
}
