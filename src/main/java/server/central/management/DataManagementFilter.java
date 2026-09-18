package server.central.management;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** 새 시험/업로드 요청과 삭제의 검사·실행 구간이 겹치지 않게 한다. */
@Component @Order(20) @RequiredArgsConstructor
public class DataManagementFilter extends OncePerRequestFilter {
    private final DataManagementGuard guard;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !path.startsWith("/lnis/api/v1/") || path.startsWith("/lnis/api/v1/data-management");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var lock=guard.gate.readLock();lock.lock();
        try {chain.doFilter(request,response);} finally {lock.unlock();}
    }
}
