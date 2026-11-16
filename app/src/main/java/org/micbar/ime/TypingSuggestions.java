package org.micbar.ime;

import android.content.Context;
import java.io.*;
import java.util.*;

final class TypingSuggestions {
    private final Map<String,String> common=new HashMap<>();
    private final Map<String,List<String>> following=new HashMap<>();
    private static final String[] TERMS={"Python","Codex","GitHub","Antigravity","JavaScript","TypeScript","OpenAI","ChatGPT","NumPy","PyTorch","WiFi","Linux","Docker"};
    void load(Context c) throws IOException {
        try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("pinyin-common.tsv"),"UTF-8"))) {
            String line;while((line=r.readLine())!=null) {
                int tab=line.indexOf('\t');if(tab>0) common.put(line.substring(0,tab),line.substring(tab+1));
            }
        }
        try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("continuations.tsv"),"UTF-8"))) {
            String line;while((line=r.readLine())!=null) {
                String[] fields=line.split("\t");following.put(fields[0],Arrays.asList(fields).subList(1,fields.length));
            }
        }
    }
    List<String> following(String context) {
        for(int start=Math.max(0,context.length()-12);start<context.length();start++){
            List<String> options=following.get(context.substring(start));
            if(options!=null)return options;
        }
        if(!context.isEmpty() && Character.UnicodeScript.of(context.codePointBefore(context.length()))==Character.UnicodeScript.HAN)
            return Arrays.asList("的","了","和");
        return Collections.emptyList();
    }
    List<String> forInput(String raw) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(String term:TERMS) if(term.equalsIgnoreCase(raw)) out.add(term);
        if(raw.length()<4 || raw.length()>20 || common.containsKey(raw)) return new ArrayList<>(out);
        // Probe adjacent transpositions first, followed by extra, missing or wrong letters.
        for(int i=1;i<raw.length();i++) {
            char[] a=raw.toCharArray();a[i]=raw.charAt(i-1);a[i-1]=raw.charAt(i);
            collect(out,new String(a));
        }
        for(int i=0;i<=raw.length() && out.size()<3;i++) {
            String left=raw.substring(0,i),right=raw.substring(i);
            if(!right.isEmpty()) collect(out,left+right.substring(1));
            for(char k='a';k<='z' && out.size()<3;k++) {
                collect(out,left+k+right);
                if(!right.isEmpty()) collect(out,left+k+right.substring(1));
            }
        }
        return new ArrayList<>(out);
    }
    private void collect(Set<String> out,String key) {String s=common.get(key);if(s!=null && out.size()<3) out.add(s);}
}
