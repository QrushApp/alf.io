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

import alfio.config.authentication.support.APITokenAuthentication;
import alfio.model.user.Role;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;

/**
 * qrush fork patch P8 — additive-only, see QRUSH-FORK.md.
 * The public ticket-assign PUT answers 403 unless the request carries an authenticated org API key
 * (ROLE_API_CLIENT on an APITokenAuthentication). Ordered after springSecurityFilterChain, so the
 * principal is already resolved when the guard runs.
 */
@Configuration
public class QrushPublicTicketAssignGuard {

    private static final PathPattern PUBLIC_TICKET_ASSIGN =
        PathPatternParser.defaultInstance.parse("/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}");

    // a bean named like this @Configuration class collides with it at boot
    @Bean
    public FilterRegistrationBean<Filter> qrushPublicTicketAssignGuardFilter() {
        Filter filter = QrushPublicTicketAssignGuard::guard;
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }

    static boolean isPublicTicketAssign(HttpServletRequest request) {
        if (!"PUT".equals(request.getMethod())) {
            return false;
        }
        // match the path MVC routes: context stripped, each segment decoded, matrix parameters dropped
        var path = RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication();
        if (PUBLIC_TICKET_ASSIGN.matches(path)) {
            return true;
        }
        var elements = path.elements();
        return !elements.isEmpty()
            && elements.get(elements.size() - 1) instanceof PathContainer.Separator
            && PUBLIC_TICKET_ASSIGN.matches(path.subPath(0, elements.size() - 1));
    }

    static boolean isOrgApiKey() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof APITokenAuthentication
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(a -> Role.API_CONSUMER.getRoleName().equals(a.getAuthority()));
    }

    private static void guard(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
            && response instanceof HttpServletResponse httpResponse
            && isPublicTicketAssign(httpRequest)
            && !isOrgApiKey()) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
