package org.micbar.ime;

import android.text.Editable;
import android.text.Spanned;
import android.icu.text.BreakIterator;
import java.util.Locale;

/** Session-only markers. Commit reads the final spelling, after piecemeal typing and undo. */
final class DraftLearning {
    private static final class Spoken {}
    private static final class Edit {
        final String heard;
        Edit(String heard) {this.heard=heard;}
    }
    private Edit pending;
    private int left,right;
    private boolean appended;
    private final BreakIterator words=BreakIterator.getWordInstance(Locale.CHINESE);

    void before(CharSequence text,int start,int removed,int added,boolean manual) {
        pending=null;appended=false;
        if(!(text instanceof Editable)) return;
        Editable e=(Editable)text;
        int end=start+removed;
        Edit prior=null;
        for(Edit edit:e.getSpans(start,end,Edit.class)) {
            int a=e.getSpanStart(edit),b=e.getSpanEnd(edit);
            boolean overlaps=a<end && b>start || (start==end && a<start && start<b);
            if(!manual) {if(overlaps) e.removeSpan(edit);continue;}
            if(prior!=null) {e.removeSpan(prior);e.removeSpan(edit);return;}
            prior=edit;
        }
        if(!manual) return;
        int a=start,b=end;
        if(prior!=null) {
            a=Math.min(a,e.getSpanStart(prior));b=Math.max(b,e.getSpanEnd(prior));
        } else {
            // For an internal spelling correction keep the whole Latin identifier.
            if(a<b || a>0 && a<e.length() && latin(e.charAt(a-1)) && latin(e.charAt(a))) {
                while(a>0 && latin(e.charAt(a-1))) a--;
                while(b<e.length() && latin(e.charAt(b))) b++;
            }
            if(b-a==1 && Character.UnicodeScript.of(e.charAt(a))==Character.UnicodeScript.HAN) {
                words.setText(e.toString());a=words.preceding(a+1);b=words.following(b-1);
            }
        }
        if(a<0 || b<a) return;
        String original;
        if(prior!=null) {
            int x=e.getSpanStart(prior),y=e.getSpanEnd(prior);e.removeSpan(prior);
            appended=removed==0 && start==y && y>x;
            if(!spoken(e,a,x) || !spoken(e,y,b)) return;
            original=e.subSequence(a,x).toString()+prior.heard+e.subSequence(y,b);
        } else {
            if(a==b || !spoken(e,a,b)) return;
            original=e.subSequence(a,b).toString();
        }
        if(!term(original)) return;
        pending=new Edit(original);left=a;right=b-removed+added;
    }
    void after(Editable e,int start,int added,boolean recognized) {
        // A space, punctuation or a switch of script ends the replacement. Leave subsequent typing outside it.
        if(pending!=null && appended && added>0) {
            boolean wasLatin=latin(e.charAt(start-1));
            for(int i=start;i<start+added;i++) {
                char c=e.charAt(i);
                if(!Character.isLetterOrDigit(c) && c!='_' || wasLatin!=latin(c)) {right=i;break;}
            }
        }
        if(pending!=null && left>=0 && right<=e.length() && right>=left && right-left<=48) {
            e.setSpan(pending,left,right,left==right?Spanned.SPAN_MARK_MARK:Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        pending=null;
        if(recognized && added>0) e.setSpan(new Spoken(),start,start+added,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private static boolean spoken(Editable e,int a,int b) {
        if(a==b) return true;
        int position=a;
        while(position<b) {
            int next=position;
            for(Spoken s:e.getSpans(position,position+1,Spoken.class))
                if(e.getSpanStart(s)<=position) next=Math.max(next,e.getSpanEnd(s));
            if(next==position) return false;
            position=next;
        }
        return true;
    }
    private static boolean latin(char c) {return c<128 && (Character.isLetterOrDigit(c) || c=='_');}
    private static boolean term(String value) {
        if(!PersonalWords.word(value) || value.length()>32 || value.codePoints().noneMatch(Character::isLetter)) return false;
        return value.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).count()<=8;
    }
    void save(Editable e,PersonalWords words) {
        for(Edit edit:e.getSpans(0,e.length(),Edit.class)) {
            String finalWord=e.subSequence(e.getSpanStart(edit),e.getSpanEnd(edit)).toString().trim();
            if(edit.heard.codePointCount(0,edit.heard.length())>=2 && term(finalWord) && !edit.heard.equals(finalWord))
                words.learnCorrection(edit.heard,finalWord);
        }
    }
}
