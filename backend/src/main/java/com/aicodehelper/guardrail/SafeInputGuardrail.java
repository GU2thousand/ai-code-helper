package com.aicodehelper.guardrail;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.GuardrailViolationException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public final class SafeInputGuardrail implements InputGuardrail {

    private static final List<Pattern> PROMPT_ATTACK_PATTERNS = List.of(
            Pattern.compile("(?is)(忽略|无视|绕过).{0,24}(之前|上面|系统|开发者).{0,24}(指令|提示词|规则)"),
            Pattern.compile("(?is)(reveal|show|print|leak|expose).{0,32}(system prompt|developer message|hidden instruction|api[ -]?key|secret)"),
            Pattern.compile("(?is)\\b(jailbreak|developer mode|DAN mode)\\b")
    );

    private final int maxCharacters;

    public SafeInputGuardrail(AppProperties properties) {
        this.maxCharacters = properties.getAi().getMaxInputCharacters();
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = extract(userMessage);
        String failure = violation(text);
        return failure == null ? success() : failure(failure);
    }

    public String checkOrThrow(String input) {
        String failure = violation(input);
        if (failure != null) {
            throw new GuardrailViolationException(failure);
        }
        return input.trim();
    }

    private String violation(String input) {
        if (input == null || input.isBlank()) {
            return "消息不能为空";
        }
        if (input.length() > maxCharacters) {
            return "消息过长，最多允许 " + maxCharacters + " 个字符";
        }
        if (containsForbiddenControlCharacter(input)) {
            return "消息包含不支持的控制字符";
        }
        for (Pattern pattern : PROMPT_ATTACK_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return "消息疑似试图绕过系统安全规则";
            }
        }
        return null;
    }

    private boolean containsForbiddenControlCharacter(String input) {
        return input.codePoints().anyMatch(value -> Character.isISOControl(value)
                && value != '\n'
                && value != '\r'
                && value != '\t');
    }

    private String extract(UserMessage message) {
        if (message == null) {
            return "";
        }
        return message.hasSingleText() ? message.singleText() : message.toString();
    }
}
