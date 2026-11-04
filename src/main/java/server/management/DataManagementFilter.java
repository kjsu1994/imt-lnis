package server.management;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import lombok.RequiredArgsConstructor;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 새 시험/업로드 요청과 삭제의 검사·실행 구간이 겹치지 않게 한다. */
@Component
@Order(20)
@RequiredArgsConstructor
public class DataManagementFilter extends OncePerRequestFilter {
    private final DataManagementGuard guard;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/lnis/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String revision = guard.revision();
        if (revision != null) response.setHeader("X-LNIS-Data-Revision", revision);
        String path = request.getRequestURI();
        boolean management = path.startsWith("/lnis/api/v1/data-management/");
        boolean reset = path.startsWith("/lnis/api/v1/data-management/reset/");
        if (guard.resetting() && !reset && !(management && "GET".equals(request.getMethod()))) {
            response.setStatus(409);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"초기화 처리 중입니다. 데이터 관리에서 진행 상태를 확인하세요.\"}");
            return;
        }
        if (management) {
            chain.doFilter(request, response);
            return;
        }
        var lock = guard.gate.readLock();
        lock.lock();
        try {
            if (guard.resetting()) {
                response.sendError(409, "초기화 처리 중입니다.");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            lock.unlock();
        }
    }
}
