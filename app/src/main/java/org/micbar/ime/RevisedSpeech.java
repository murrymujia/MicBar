package org.micbar.ime;

/** Merge a service's cumulative hypothesis with the user's edits to the previous one. */
final class RevisedSpeech {
    private RevisedSpeech() {}

    static String merge(String before, String edited, String next) {
        if (edited.equals(before)) return next;
        if (next.startsWith(before)) return edited + next.substring(before.length());
        int left = 0, suffix = 0;
        while (left < before.length() && left < edited.length()
                && before.charAt(left) == edited.charAt(left)) left++;
        while (suffix < before.length()-left && suffix < edited.length()-left
                && before.charAt(before.length()-1-suffix) == edited.charAt(edited.length()-1-suffix)) suffix++;

        // Align the user-edited interval with the revised hypothesis. Adjacent new
        // speech belongs outside that interval. Keep previous edits on ambiguity.
        int n = before.length(), m = next.length();
        if ((long)(n+1)*(m+1) > 300000) return edited;
        int[][] cost = new int[n+1][m+1];
        for (int i=n;i>=0;i--) for (int j=m;j>=0;j--) {
            if (i==n) cost[i][j]=m-j;
            else if (j==m) cost[i][j]=n-i;
            else if (before.charAt(i)==next.charAt(j)) cost[i][j]=cost[i+1][j+1];
            else cost[i][j]=1+Math.min(cost[i+1][j+1],Math.min(cost[i+1][j],cost[i][j+1]));
        }
        int start=0,end=0;
        for (int i=0,j=0;;) {
            if (i==left) start=j;
            if (i==n-suffix) { end=j; break; }
            if (j<m && cost[i][j]==cost[i+1][j+1]+(before.charAt(i)==next.charAt(j)?0:1)) { i++; j++; }
            else if (cost[i][j]==cost[i+1][j]+1) i++;
            else j++;
        }
        return next.substring(0,start)+edited.substring(left,edited.length()-suffix)+next.substring(end);
    }
}
