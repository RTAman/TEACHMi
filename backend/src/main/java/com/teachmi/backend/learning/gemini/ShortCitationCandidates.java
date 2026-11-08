package com.teachmi.backend.learning.gemini;

import java.util.ArrayList;
import java.util.List;
import com.teachmi.backend.learning.LearningSourceReader;

/** Deterministic literal excerpts for generation, never repairs an AI response or rewrites a source. */
final class ShortCitationCandidates {
    record Candidate(String sourceChunkId,String quote) {}
    static List<Candidate> from(List<LearningSourceReader.Chunk> chunks) {
        var result=new ArrayList<Candidate>();
        for(var chunk:chunks) {
            String text=chunk.text();int added=0;
            for(int start=0;start<text.length() && added<8 && result.size()<64;) {
                int end=Math.min(start+40,text.length());
                if(end<text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
                // Prefer a line/sentence/word boundary, keeping every retained character literal.
                if(end<text.length()) {
                    for(int i=end-1;i>=start+6;i--) {
                        char c=text.charAt(i);
                        if(Character.isWhitespace(c) || ".!?。！？".indexOf(c)>=0) {end=i+1;break;}
                    }
                }
                String quote=text.substring(start,end).strip();
                if(quote.length()>=6) {result.add(new Candidate(chunk.id(),quote));added++;}
                start=end;
            }
        }
        return List.copyOf(result);
    }
    private ShortCitationCandidates() {}
}
