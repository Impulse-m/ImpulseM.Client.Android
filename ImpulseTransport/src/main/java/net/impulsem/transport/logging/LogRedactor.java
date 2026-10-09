package net.impulsem.transport.logging;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Strips personal data and secrets from log text before it leaves the device: message text, names, phone numbers,
 * e-mail, file names, tokens, keys and codes. Mirrors the web client's impulseLogger rules (redact by field name,
 * keep error codes) and adds free-text rules, because Android log calls are strings, not objects.
 */
public final class LogRedactor {

    public static final String Redacted = "[REDACTED]";

    private static final Set<String> SensitiveKeys = new HashSet<String>(Arrays.asList(
        "text", "message", "caption", "body", "content", "bytes", "about", "title", "name", "locargs",
        "firstname", "lastname", "username", "usernames", "phone", "phonenumber", "email", "filename", "path",
        "password", "passcode", "authkey", "sessionkey", "pushauthkey", "aeskey", "privatekey", "encryptionkey",
        "phonecode", "smscode", "emailcode", "recoverycode", "logincode", "codehash", "phonecodehash"
    ));
    private static final Set<String> ContentKeys = new HashSet<String>(Arrays.asList(
        "text", "message", "caption", "body", "content", "about", "locargs"
    ));

    private static final Pattern JsonMember = Pattern.compile(
        "\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\]]*\\]|-?\\d+)"
    );
    private static final Pattern KeyValue = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_\\-]*)(\\s*[=:]\\s*)([^\\r\\n]*)");
    private static final Pattern TokenWord = Pattern.compile("(?i)\\b(\\w*token)(\\s+)([^\\s,;]{8,})");
    private static final Pattern Bearer = Pattern.compile("(?i)Bearer\\s+\\S+");
    private static final Pattern Jwt = Pattern.compile("\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\b");
    private static final Pattern Blob = Pattern.compile("[A-Za-z0-9+/=_\\-]{32,}");
    private static final Pattern ErrorCode = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern Email = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern Phone = Pattern.compile("\\+?\\d[\\d\\s()\\-]{7,}\\d");
    private static final Pattern StoragePath = Pattern.compile("/(?:storage|sdcard|data/user|data/data)/\\S+");
    private static final Pattern ScalarValue = Pattern.compile("[^\\s,;)}\\]]+");


    private LogRedactor() {
    }


    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = normalizeKey(key);
        return SensitiveKeys.contains(normalized)
            || normalized.endsWith("token")
            || normalized.endsWith("secret")
            || normalized.endsWith("password")
            || normalized.endsWith("phone")
            || normalized.endsWith("name")
            || normalized.endsWith("title");
    }


    /** A structured field: a boolean or a number survives under any name that is not sensitive, a string is scrubbed. */
    public static Object sanitizeField(
        String key,
        Object value
    ) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (isSensitiveKey(key)) {
            return Redacted;
        }
        if (value instanceof Number) {
            return value;
        }
        return sanitizeText(String.valueOf(value));
    }


    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = redactJsonMembers(text);
        result = redactKeyValues(result);
        result = redactTokenWords(result);
        result = Bearer.matcher(result).replaceAll(Redacted);
        result = Jwt.matcher(result).replaceAll(Redacted);
        result = Email.matcher(result).replaceAll(Redacted);
        result = StoragePath.matcher(result).replaceAll(Redacted);
        result = redactBlobs(result);
        result = Phone.matcher(result).replaceAll(Redacted);
        return result;
    }


    private static String redactJsonMembers(String text) {
        Matcher matcher = JsonMember.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(0);
            if (isSensitiveKey(matcher.group(1)) && !isErrorCodeLiteral(matcher.group(2))) {
                replacement = "\"" + matcher.group(1) + "\":\"" + Redacted + "\"";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }


    private static String redactKeyValues(String text) {
        Matcher matcher = KeyValue.matcher(text);
        StringBuffer out = new StringBuffer();
        int resumeAt = 0;
        while (resumeAt <= text.length() && matcher.find(resumeAt)) {
            String key = matcher.group(1);
            String separator = matcher.group(2);
            String rest = matcher.group(3);
            out.append(text, resumeAt, matcher.start());
            if (!isSensitiveKey(key)) {
                out.append(key).append(separator);
                resumeAt = matcher.start(3);
                continue;
            }
            int valueLength = valueLength(key, rest);
            String value = rest.substring(0, valueLength);
            out.append(key).append(separator).append(ErrorCode.matcher(value).matches() ? value : Redacted);
            resumeAt = matcher.start(3) + valueLength;
        }
        if (resumeAt < text.length()) {
            out.append(text, resumeAt, text.length());
        }
        return out.toString();
    }


    private static int valueLength(
        String key,
        String rest
    ) {
        if (ContentKeys.contains(normalizeKey(key))) {
            return rest.length();
        }
        Matcher scalar = ScalarValue.matcher(rest);
        return scalar.lookingAt() ? scalar.end() : 0;
    }


    private static String redactTokenWords(String text) {
        Matcher matcher = TokenWord.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group(3);
            String replacement = value.startsWith(Redacted) ? matcher.group(0) : matcher.group(1) + matcher.group(2) + Redacted;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }


    private static String redactBlobs(String text) {
        Matcher matcher = Blob.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String blob = matcher.group(0);
            String replacement = looksLikeSecret(blob) ? Redacted : blob;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }


    private static boolean looksLikeSecret(String blob) {
        if (ErrorCode.matcher(blob).matches()) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < blob.length(); i++) {
            char c = blob.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isLetter(c)) {
                hasLetter = true;
            }
        }
        return hasDigit && hasLetter;
    }


    private static boolean isErrorCodeLiteral(String jsonValue) {
        if (jsonValue.length() < 2 || jsonValue.charAt(0) != '"') {
            return false;
        }
        return ErrorCode.matcher(jsonValue.substring(1, jsonValue.length() - 1)).matches();
    }


    private static String normalizeKey(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.US);
    }
}
