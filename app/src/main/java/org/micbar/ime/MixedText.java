package org.micbar.ime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MixedText {
    private static final String[] TERMS = {"Python", "GitHub", "JavaScript", "TypeScript", "Docker", "Codex", "Linux", "Antigravity"};
    private static final Pattern WORD = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z]{4,}(?![A-Za-z0-9_])");
    private static final Pattern CODE_CONTEXT = Pattern.compile("代码|脚本|程序|仓库|项目|函数|模块|编译|开发|运行|安装|环境");
    private MixedText() { }

    static String dictation(String hypothesis,boolean correctSpelling,boolean keepPeriods) {
        String text=hypothesis.trim();
        if(!keepPeriods) {
            // Remove sentence stops in speech output, preserving decimals, filenames and the
            // internal dots of URLs. Latin sentence boundaries retain their existing space.
            text=text.replaceAll("(?<=[A-Za-z0-9])。+(?=[A-Za-z0-9])"," ").replace("。","").replaceAll("(?<![.])(?<![A-Za-z][.][A-Za-z])\\.(?![.])(?=\\s|$|\\p{IsHan})","");
        }
        return format(text,correctSpelling);
    }

    static String finishSentence(String text) {
        int end=text.length();
        while(end>0 && Character.isWhitespace(text.charAt(end-1))) end--;
        int word=end;
        while(word>0 && "”’」』）》】)]}\"'".indexOf(text.charAt(word-1))>=0) word--;
        if(word==0 || !Character.isLetterOrDigit(text.codePointBefore(word))) return text;
        boolean chinese=text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);
        return text.substring(0,end)+(chinese?"。":".")+text.substring(end);
    }

    static String format(String text, boolean correctSpelling) {
        String formatted = text;
        if (correctSpelling) {
            Matcher words = WORD.matcher(text);
            StringBuffer out = new StringBuffer();
            while (words.find()) {
                String vicinity = text.substring(Math.max(0, words.start() - 9), Math.min(text.length(), words.end() + 9));
                String spelling = CODE_CONTEXT.matcher(vicinity).find() ? spellingOf(words.group()) : words.group();
                words.appendReplacement(out, Matcher.quoteReplacement(spelling));
            }
            words.appendTail(out);
            formatted = out.toString();
        }
        return formatted.replaceAll("(?<=\\p{IsHan})(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=\\p{IsHan})", " ");
    }

    private static String spellingOf(String token) {
        String heard = token.toLowerCase(Locale.ROOT);
        String match = null;
        for (String term : TERMS) {
            String canonical = term.toLowerCase(Locale.ROOT);
            if (heard.equals(canonical)) return term;
            // Accept dropped interior letters or a stutter, never substitute unrelated letters.
            if (!heard.startsWith(canonical.substring(0, 2)) || !heard.endsWith(canonical.substring(canonical.length()-1))) continue;
            int missing = canonical.length() - heard.length();
            boolean plausible = missing > 0 && missing <= 2 && isSubsequence(heard, canonical);
            if (!plausible && missing < 0 && missing >= -2) plausible = heard.replaceAll("(.)\\1+", "$1").equals(canonical);
            if (plausible) {
                if (match != null) return token;
                match = term;
            }
        }
        return match == null ? token : match;
    }

    private static boolean isSubsequence(String shortWord, String fullWord) {
        int i = 0;
        for (int j = 0; j < fullWord.length() && i < shortWord.length(); j++) {
            if (shortWord.charAt(i) == fullWord.charAt(j)) i++;
        }
        return i == shortWord.length();
    }
}
