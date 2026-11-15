package com.teachmi.backend.learning;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Server-generated support reference. Never log a URI, cookie, request body or client request id. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LearningRequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER="X-Teachmi-Request-Id";
    private static final String CONTEXT="teachmiRequestId";
    private static final String PREFIX="/api/v1/sessions";
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(LearningRequestTraceFilter.class);

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(PREFIX) && !path.startsWith(PREFIX+"/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String previous=MDC.get(CONTEXT);
        String oldApproval=com.teachmi.backend.learning.gemini.ApprovedRecoveryContext.raw();
        String path=request.getRequestURI().substring(request.getContextPath().length());
        com.teachmi.backend.learning.gemini.ApprovedRecoveryContext.set("POST".equals(request.getMethod())
                && path.matches("/api/v1/sessions/[0-9a-f-]{36}/problems/[0-9a-f-]{36}/attempts")
                ?request.getHeader(com.teachmi.backend.learning.gemini.ApprovedRecoveryContext.HEADER):null);
        String requestId=UUID.randomUUID().toString();
        long started=System.nanoTime();
        response.setHeader(HEADER,requestId);
        MDC.put(CONTEXT,requestId);
        boolean completed=false;
        try {
            chain.doFilter(request,response);
            completed=true;
        } finally {
            // The id joins this completion line to Gemini stage logs without logging the browser URL or payload.
            LOG.info("Learning request requestId={} httpStatus={} elapsedMs={}",requestId,completed?Integer.toString(response.getStatus()):"unresolved",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            if(previous==null) MDC.remove(CONTEXT); else MDC.put(CONTEXT,previous);
            com.teachmi.backend.learning.gemini.ApprovedRecoveryContext.set(oldApproval);
        }
    }

    public static String currentRequestId() {
        String id=MDC.get(CONTEXT);
        try {return id!=null && UUID.fromString(id).toString().equals(id)?id:"none";}
        catch(IllegalArgumentException invalid) {return "none";}
    }
}
