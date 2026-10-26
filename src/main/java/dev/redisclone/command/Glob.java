package dev.redisclone.command;

import java.util.regex.Pattern;

/** Redis-style glob patterns (* ? [abc] [a-z] [^a] and \x escapes) used by KEYS. */
final class Glob {
    private Glob() {}

    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    int end = glob.indexOf(']', i + 1);
                    if (end == -1) {
                        regex.append("\\[");
                    } else {
                        String body = glob.substring(i + 1, end);
                        if (body.startsWith("^")) {
                            body = "^" + body.substring(1).replace("\\", "\\\\");
                        } else {
                            body = body.replace("\\", "\\\\");
                        }
                        regex.append('[').append(body.replace("[", "\\[")).append(']');
                        i = end;
                    }
                }
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    } else {
                        regex.append("\\\\");
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
