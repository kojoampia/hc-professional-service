package net.jojoaddison.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import net.jojoaddison.service.ProfileStatusAnnouncer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The unit of work {@link ProfileStatusAnnouncer} announces once per (backlog.md item 49).
 *
 * <p>Without it every save would publish, and two ordinary journeys write twice: a superseding
 * renewal saves the replacement and then the archived row, and assigning an organisation saves the
 * profile and then the application. Two frames for one decision is the smaller problem — the larger
 * one is that the first of them reports a state that existed only between the two writes, which is
 * not a moment anybody asked about.
 *
 * <p><b>Nothing is skipped when this filter does not run.</b> Off a request thread the announcer
 * publishes per save instead of per request, which is noisier and never wrong. That is the right way
 * round for a mechanism whose whole purpose is that a path nobody thought about still speaks.
 *
 * <p>Ordered last so that it is the innermost wrapper around the handler: it needs to enclose the
 * writes and nothing else, and every filter outside it — forwarded headers, ETag, CORS, security —
 * has ordering reasons of its own that this must not compete with. {@code OncePerRequestFilter}
 * leaves async dispatches alone by default, so a write made on an async thread announces per save;
 * nothing in this service handles a request that way today.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProfileStatusAnnouncementFilter extends OncePerRequestFilter {

    private final ProfileStatusAnnouncer announcer;

    public ProfileStatusAnnouncementFilter(ProfileStatusAnnouncer announcer) {
        this.announcer = announcer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        boolean opened = announcer.open();
        try {
            chain.doFilter(request, response);
        } finally {
            // In a finally block on purpose: a request that wrote and then failed has still changed
            // what the directory should be showing, and the refusal the caller sees says nothing
            // about the row that was already persisted.
            if (opened) {
                announcer.flush();
            }
        }
    }
}
