package com.teachmi.backend.learning.gemini;

import java.util.UUID;

/** A requested approval is not authority; the ledger checks the owner consent receipt and exact scope. */
public final class ApprovedRecoveryContext {
    public static final String HEADER="X-Teachmi-Recovery-Authorization";
    private static final ThreadLocal<String> VALUE=new ThreadLocal<>();
    private ApprovedRecoveryContext() {}
    public static void set(String value) { if(value==null) VALUE.remove(); else VALUE.set(value); }
    public static String raw() { return VALUE.get(); }
    static UUID requested() {
        String value=VALUE.get();
        if(value==null) return null;
        try {
            UUID id=UUID.fromString(value);
            if(!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch(IllegalArgumentException invalid) { throw ApprovedGradingRecovery.denied(); }
    }
}
