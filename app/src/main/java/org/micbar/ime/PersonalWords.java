package org.micbar.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.text.Transliterator;
import java.util.*;
import java.util.regex.*;

/** Local spelling preferences shared by dictation and the candidate strip. */
final class PersonalWords {
    private final SharedPreferences store;
    private final SharedPreferences settings;
    private static final int LIMIT=512;
    PersonalWords(Context context) {
        store=context.getSharedPreferences("personal_words",0);
        settings=context.getSharedPreferences("micbar",0);
    }
    static boolean word(String value) {
        return value!=null && value.length()<=48 && value.matches("[\\p{L}\\p{M}\\p{N}_+#.\\-]+");
    }
    static String key(String s) {return s.toLowerCase(Locale.ROOT).replace("'","");}
    boolean learns() {return settings.getBoolean("learnWords",true);}
    void selected(String raw,String word) {
        if(!learns() || !word(raw) || !word(word)) return;
        String name="s:"+key(raw)+":"+word;
        if(!store.contains(name) && store.getAll().size()>=LIMIT) return;
        store.edit().putInt(name,Math.min(10000,store.getInt(name,0)+1)).apply();
    }
    void learnCorrection(String alias,String spelling) {
        if(!learns() || !word(alias) || !word(spelling)) return;
        String name="c:"+alias;
        if(store.contains("a:"+alias) || !roomFor(name)) return;
        store.edit().putString(name,spelling).apply();
    }
    private boolean roomFor(String name) {
        if(store.contains(name)) return true;
        Map<String,?> entries=store.getAll();
        if(entries.size()<LIMIT) return true;
        String weakest=null;int score=Integer.MAX_VALUE;
        for(Map.Entry<String,?> entry:entries.entrySet()) {
            if(entry.getKey().startsWith("s:") && (Integer)entry.getValue()<score) {
                weakest=entry.getKey();score=(Integer)entry.getValue();
            }
        }
        if(weakest==null) return false;
        store.edit().remove(weakest).apply();return true;
    }
    boolean remember(String alias,String spelling) {
        if(!word(alias) || !word(spelling)) return false;
        String name="a:"+alias;
        if(!store.contains("c:"+alias) && !roomFor(name)) return false;
        store.edit().remove("c:"+alias).putString(name,spelling).apply();return true;
    }
    Map<String,?> entries() {return store.getAll();}
    void forget(String name) {store.edit().remove(name).apply();}
    void clear() {store.edit().clear().apply();}
    private static final class Romanizer {
        static final Transliterator INSTANCE=Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower");
    }
    private final Map<String,String> romanized=new HashMap<>();
    private String letters(String s) {
        if(android.os.Build.VERSION.SDK_INT<29 || s.matches("[\\x00-\\x7f]+")) return key(s);
        return romanized.computeIfAbsent(s,x->Romanizer.INSTANCE.transliterate(x).replace(" ",""));
    }
    List<String> candidates(String raw) {
        String code=key(raw);
        Map<String,Integer> scores=new HashMap<>();
        for(Map.Entry<String,?> e:store.getAll().entrySet()) {
            String name=e.getKey();
            if(name.startsWith("a:") || learns() && name.startsWith("c:")) {
                String alias=name.substring(2), value=(String)e.getValue();
                if(code.equals(letters(alias)) || code.equals(key(value)) || code.equals(letters(value))) scores.merge(value,name.startsWith("a:")?30000:20000,Math::max);
            } else if(learns() && name.startsWith("s:"+code+":")) {
                String value=name.substring(code.length()+3);
                scores.put(value,Math.max(scores.getOrDefault(value,0),(Integer)e.getValue()));
            }
        }
        List<String> out=new ArrayList<>(scores.keySet());
        out.sort(Comparator.<String>comparingInt(scores::get).reversed().thenComparing(x->x));return out;
    }
    String speech(String input) {
        Map<String,String> aliases=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String,?> entries=store.getAll();
        if(learns()) {
            Map<String,Integer> ranks=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(Map.Entry<String,?> e:entries.entrySet()) if(e.getKey().startsWith("s:")) {
                String value=e.getKey().substring(e.getKey().lastIndexOf(':')+1);
                if(!value.matches("[A-Za-z][A-Za-z0-9_+#.-]{1,47}")) continue;
                int score=(Integer)e.getValue();
                if(score>ranks.getOrDefault(value,0)) {ranks.put(value,score);aliases.put(value,value);}
            }
        }
        if(learns()) for(Map.Entry<String,?> e:entries.entrySet()) if(e.getKey().startsWith("c:"))
            aliases.put(e.getKey().substring(2),(String)e.getValue());
        for(Map.Entry<String,?> e:entries.entrySet()) if(e.getKey().startsWith("a:")) aliases.put(e.getKey().substring(2),(String)e.getValue());
        if(aliases.isEmpty()) return input;
        List<String> keys=new ArrayList<>(aliases.keySet());keys.sort(Comparator.comparingInt(String::length).reversed());
        List<String> alternatives=new ArrayList<>();
        for(String k:keys) {
            String pattern=Pattern.quote(k);
            if(k.substring(0,1).matches("[a-zA-Z0-9_]")) pattern="(?<![a-zA-Z0-9_])"+pattern;
            if(k.substring(k.length()-1).matches("[a-zA-Z0-9_]")) pattern+="(?![a-zA-Z0-9_])";
            alternatives.add(pattern);
        }
        Matcher m=Pattern.compile(String.join("|",alternatives),Pattern.CASE_INSENSITIVE).matcher(input);
        StringBuffer out=new StringBuffer();while(m.find()) m.appendReplacement(out,Matcher.quoteReplacement(aliases.get(m.group())));
        m.appendTail(out);return out.toString();
    }
}
