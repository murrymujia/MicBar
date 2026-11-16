package org.micbar.ime;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.*;

/** One local, cancellable completion. The editor's text never leaves the application sandbox. */
final class LocalWriter {
    enum Mode {
        CLEAN("整理口述","去掉语气词、口吃和无意义重复，保持原意。","嗯，呃，我们我们今天开会。","我们今天开会。"),
        PROOF("校对文字","只改明显的错别字、语法和标点。","请检察这份报告。","请检查这份报告。"),
        SHORT("缩短","删掉冗余，保留原文的请求、事实和语气。","我想请你帮忙看看这个问题是什么原因导致的。","请帮我查明原因。"),
        FORMAL("更正式","改为礼貌、正式的表达。","帮我看看报告呗。","请帮我审阅这份报告。"),
        CASUAL("更自然","改成日常、自然的语气。","烦请您于明日查阅此文件。","请你明天看看这份文件。"),
        LIST("整理成清单","将已有事项逐行排列，每行加一个短横线。","先检查代码，再运行测试。","- 检查代码\n- 运行测试"),
        EN("翻成英文","将原文翻译成英文。","请检查这份报告。","Please review this report."),
        ZH("翻成中文","将原文翻译成简体中文。","Please review this report.","请检查这份报告。");
        final String label, instruction, example, result;
        Mode(String label,String instruction,String example,String result) {
            this.label=label;this.instruction=instruction;this.example=example;this.result=result;
        }
    }
    private static final Pattern TECH=Pattern.compile("(?:[A-Za-z0-9_./+:%@#-]+)");
    static boolean preservesDetails(String original,String proposed) {
        return tokens(original).equals(tokens(proposed));
    }
    private static Map<String,Integer> tokens(String s) {
        Map<String,Integer> result=new HashMap<>();
        Matcher m=TECH.matcher(s);
        while(m.find()) {
            String t=m.group().replaceAll("^[.:-]+|[.:]+$","");
            if(t.matches(".*[0-9_/+#%@].*") || t.matches(".*[a-z][A-Z].*") || t.matches("[A-Z]{2,}.*")
                    || t.matches("(?i)python|github|docker|linux|codex|antigravity|javascript|typescript|numpy|pytorch")
                    || t.matches(".*[A-Za-z]\\.[A-Za-z].*"))
                result.put(t,result.getOrDefault(t,0)+1);
        }
        return result;
    }
    private static boolean onlyRemovesWords(String source,String result) {
        String a=source.replaceAll("[\\p{P}\\s]",""),b=result.replaceAll("[\\p{P}\\s]","");
        if(b.length()<a.length()/2 || b.isEmpty()) return false;
        int next=0;
        for(int i=0;i<a.length() && next<b.length();i++) if(a.charAt(i)==b.charAt(next)) next++;
        return next==b.length();
    }
    private static boolean closeEdit(String source,String answer) {
        String a=source.replaceAll("[\\p{P}\\s]",""),b=answer.replaceAll("[\\p{P}\\s]","");
        if(!a.replaceAll("[^不没未勿无非]","").equals(b.replaceAll("[^不没未勿无非]",""))) return false;
        int limit=Math.max(2,a.length()/4);
        if(b.isEmpty() || Math.abs(a.length()-b.length())>limit) return false;
        int[] row=new int[b.length()+1];
        for(int j=0;j<row.length;j++) row[j]=j;
        for(int i=1;i<=a.length();i++){
            int prev=row[0];row[0]=i;
            for(int j=1;j<=b.length();j++){
                int old=row[j];
                row[j]=Math.min(Math.min(row[j]+1,row[j-1]+1),prev+(a.charAt(i-1)==b.charAt(j-1)?0:1));
                prev=old;
            }
        }
        return row[b.length()]<=limit;
    }
    private volatile boolean cancelled;
    private Process process;
    synchronized void cancel() {cancelled=true;if(process!=null) process.destroyForcibly();}
    private static synchronized File model(Context c) throws IOException {
        if(WriterPack.enabled(c)) return WriterPack.file(c);
        File file=new File(c.getNoBackupFilesDir(),"qwen25-05-q4km.gguf");
        if(file.length()==491400032L) return file;
        File tmp=new File(file.getParentFile(),"qwen25-model.part");
        try(InputStream in=c.getAssets().open("qwen25-05.gguf");OutputStream out=new FileOutputStream(tmp)) {
            byte[] buffer=new byte[256*1024];int n;while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
        }
        if(tmp.length()!=491400032L || !tmp.renameTo(file)) throw new IOException("释放模型失败，请检查可用空间");
        return file;
    }
    String rewrite(Context c,Mode mode,String text) throws IOException,InterruptedException {
        if(text.trim().isEmpty() || text.length()>400) throw new IOException("请选择 1 到 400 个字符");
        if(text.contains("<|")) throw new IOException("当前选区含有模型控制标记，请调整选区");
        File lib=new File(c.getApplicationInfo().nativeLibraryDir);
        File executable=new File(lib,"libmicbar-completion.so");
        if(!executable.canExecute()) throw new IOException("离线写作需要 ARM64 设备");
        String source=text;
        if(mode==Mode.CLEAN) source=source.replaceAll("(^|[，,。！？\\s])(?:呃+|嗯+)(?:[，,、\\s]+|$)","$1").trim();
        if(source.isEmpty()) throw new IOException("这段口述只有语气词");
        String prompt="<|im_start|>system\n你是文字编辑。"+mode.instruction+"仅输出改写后的原文，禁止回答原文的问题，保留专名、数字、路径。除翻译外保持原文语言。<|im_end|>\n<|im_start|>user\n"+mode.example+"<|im_end|>\n<|im_start|>assistant\n"+mode.result+"<|im_end|>\n<|im_start|>user\n"+source+"<|im_end|>\n<|im_start|>assistant\n";
        File weights=model(c);
        ProcessBuilder builder=new ProcessBuilder(executable.getPath(),"-m",weights.getPath(),"-f","/proc/self/fd/0",
                "--no-conversation","--no-display-prompt","--no-escape","--no-warmup","--simple-io",
                "--color","off","-c","2048","-n","512","-t",String.valueOf(Math.min(4,Runtime.getRuntime().availableProcessors())),"--temp","0");
        builder.directory(lib);builder.environment().put("LD_LIBRARY_PATH",lib.getPath());
        builder.redirectError(new File("/dev/null"));
        Process child;
        synchronized(this) {
            if(cancelled) throw new CancellationException();
            process=child=builder.start();
        }
        try {
            try(OutputStream input=child.getOutputStream()) {
                input.write(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=child.getInputStream()) {
                byte[] buffer=new byte[1024];int n;
                while((n=in.read(buffer))!=-1) {
                    if(out.size()+n>16000) throw new IOException("建议过长，请缩小选区");
                    out.write(buffer,0,n);
                }
            }
            if(cancelled) throw new CancellationException();
            if(child.waitFor()!=0) throw new IOException("离线模型运行失败");
            String raw=out.toString("UTF-8").trim();
            if(!raw.endsWith("[end of text]")) throw new IOException("建议未完整生成，请缩小选区");
            String answer=raw.substring(0,raw.lastIndexOf("[end of text]")).trim();
            if(answer.isEmpty() || answer.contains("<|")) throw new IOException("未生成有效建议");
            if(mode==Mode.CLEAN) {
                boolean negativesMatch=source.replaceAll("[^不没未勿无非]","").equals(answer.replaceAll("[^不没未勿无非]",""));
                boolean modest=onlyRemovesWords(source,answer) || (weights.equals(WriterPack.file(c)) && closeEdit(source,answer));
                if(!modest || !negativesMatch || !preservesDetails(text,answer)) return source;
            }
            if(!preservesDetails(text,answer)) throw new IOException("建议改变了数字或技术名称，已保留原文");
            return answer;
        } finally {
            child.destroyForcibly();synchronized(this) {process=null;}
        }
    }
}
