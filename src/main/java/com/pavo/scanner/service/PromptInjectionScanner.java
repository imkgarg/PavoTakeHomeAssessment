package com.pavo.scanner.service;

import com.pavo.scanner.model.ScanResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class PromptInjectionScanner {

    private static final List<PatternRule> RULES = List.of(
            new PatternRule(
                    Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
                    "Attempt to override prior instructions"),
            new PatternRule(
                    Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
                    "Attempt to disregard prior instructions"),
            new PatternRule(
                    Pattern.compile("(?i)forget\\s+(everything|all)\\s+(you\\s+)?(know|learned|were\\s+told)"),
                    "Attempt to reset model context"),
            new PatternRule(
                    Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|the)\\s+"),
                    "Role reassignment injection"),
            new PatternRule(
                    Pattern.compile("(?i)system\\s*:\\s*"),
                    "Fake system prompt delimiter"),
            new PatternRule(
                    Pattern.compile("(?i)<\\s*/?\\s*system\\s*>"),
                    "System tag injection"),
            new PatternRule(
                    Pattern.compile("(?i)reveal\\s+(your\\s+)?(system\\s+)?prompt"),
                    "Attempt to extract system prompt"),
            new PatternRule(
                    Pattern.compile("(?i)show\\s+(me\\s+)?(your\\s+)?(hidden\\s+)?(system\\s+)?prompt"),
                    "Attempt to extract system prompt"),
            new PatternRule(
                    Pattern.compile("(?i)do\\s+not\\s+follow\\s+(the\\s+)?(rules|instructions|guidelines)"),
                    "Instruction bypass attempt"),
            new PatternRule(
                    Pattern.compile("(?i)bypass\\s+(the\\s+)?(safety|security|content)\\s+(filter|policy|guardrails?)"),
                    "Safety bypass attempt"),
            new PatternRule(
                    Pattern.compile("(?i)jailbreak"),
                    "Known jailbreak keyword"),
            new PatternRule(
                    Pattern.compile("(?i)developer\\s+mode\\s+(enabled|on)"),
                    "Developer mode injection"),
            new PatternRule(
                    Pattern.compile("(?i)act\\s+as\\s+(if\\s+you\\s+have\\s+)?no\\s+(restrictions|limits|rules)"),
                    "Restriction removal attempt"),
            new PatternRule(
                    Pattern.compile("(?i)override\\s+(your\\s+)?(instructions|programming|directives?)"),
                    "Instruction override attempt"),
            new PatternRule(
                    Pattern.compile("(?i)\\[\\s*INST\\s*\\]|<<\\s*SYS\\s*>>|<\\s*\\|\\s*im_start\\s*\\|>"),
                    "Chat template delimiter injection")
    );

    public ScanResponse scan(String content) {
        if (content == null || content.isBlank()) {
            return new ScanResponse(true, "Content is empty");
        }

        for (PatternRule rule : RULES) {
            if (rule.pattern().matcher(content).find()) {
                return new ScanResponse(false, rule.reason());
            }
        }

        return new ScanResponse(true, "No prompt injection patterns detected");
    }

    private record PatternRule(Pattern pattern, String reason) {
    }
}
