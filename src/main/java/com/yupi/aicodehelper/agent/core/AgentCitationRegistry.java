package com.yupi.aicodehelper.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentCitationRegistry {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)\\]");

    private final List<AgentCitationRef> refs = new ArrayList<>();
    private int nextRef = 1;

    public int register(String source, String title, String section) {
        int ref = nextRef++;
        refs.add(new AgentCitationRef(ref, source, title, section));
        return ref;
    }

    public List<AgentCitationRef> all() {
        return List.copyOf(refs);
    }

    public String appendReferenceList(String finalAnswer) {
        if (refs.isEmpty() || finalAnswer == null) {
            return finalAnswer == null ? "" : finalAnswer;
        }
        Matcher matcher = CITATION_PATTERN.matcher(finalAnswer);
        boolean hasCitation = false;
        while (matcher.find()) {
            int refNum = Integer.parseInt(matcher.group(1));
            hasCitation = true;
            if (refNum < 1 || refNum > refs.size()) {
                continue;
            }
        }
        if (!hasCitation) {
            return finalAnswer;
        }
        StringBuilder sb = new StringBuilder(finalAnswer);
        sb.append("\n\n---\n**References:**\n");
        for (AgentCitationRef ref : refs) {
            sb.append("- [").append(ref.ref()).append("] ").append(ref.source());
            if (ref.section() != null && !ref.section().isBlank()) {
                sb.append(" > ").append(ref.section());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
