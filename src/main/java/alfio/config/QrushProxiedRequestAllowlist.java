/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * qrush fork patch P9 — additive-only, see QRUSH-FORK.md.
 * A request that came through the proxy reaches only the check-in API, the org-key API and the
 * health check; everything else answers 404 with an empty body. Requests without a proxy header
 * (the ops UI through the SSH tunnel) pass untouched.
 */
@Configuration
public class QrushProxiedRequestAllowlist {

    // Caddy sets X-Qrush-Proxy on every forwarded request; the container may consume X-Forwarded-For
    private static final List<String> PROXY_HEADERS = List.of("X-Qrush-Proxy", "X-Forwarded-For",
        "X-Forwarded-Proto", "X-Forwarded-Host", "Forwarded");
    private static final List<String> OPEN_PREFIXES = List.of("/admin/api/check-in/", "/api/v1/admin/");
    private static final String HEALTH_CHECK = "/healthz";

    // a bean named like this @Configuration class collides with it at boot
    @Bean
    public FilterRegistrationBean<Filter> qrushProxiedRequestAllowlistFilter() {
        Filter filter = QrushProxiedRequestAllowlist::allowlist;
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static boolean isProxied(HttpServletRequest request) {
        return PROXY_HEADERS.stream().anyMatch(header -> request.getHeader(header) != null);
    }

    static boolean isOpen(HttpServletRequest request) {
        String path;
        try {
            // the path MVC routes: context stripped, each segment decoded, matrix parameters dropped
            var container = RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication();
            var decoded = new StringBuilder();
            for (var element : container.elements()) {
                decoded.append(element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
            }
            path = decoded.toString();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        // a decoded .. (also from %2e%2e, ..; or an encoded slash) could climb out of an open prefix
        if (Arrays.asList(path.split("/", -1)).contains("..")) {
            return false;
        }
        return path.equals(HEALTH_CHECK) || OPEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static void allowlist(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
            && response instanceof HttpServletResponse httpResponse
            && isProxied(httpRequest)
            && !isOpen(httpRequest)) {
            // setStatus, never sendError: an error dispatch could render a page
            httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            httpResponse.setContentLength(0);
            httpResponse.flushBuffer();
            return;
        }
        chain.doFilter(request, response);
    }
}
