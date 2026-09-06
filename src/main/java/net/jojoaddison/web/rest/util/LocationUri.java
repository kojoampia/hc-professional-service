package net.jojoaddison.web.rest.util;

import java.net.URI;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The {@code Location} of a resource this service has just created (backlog.md item 41).
 *
 * <p><b>The defect this closes.</b> Every {@code 201} here used to build its {@code Location} by
 * hand — {@code ResponseEntity.created(new URI("/api/profiles/" + profile.getId()))} — in eleven
 * resources. That is an absolute-path reference, and RFC 3986 resolves it against the origin of the
 * effective request URI, discarding everything else. A client that POSTed
 * {@code /services/professionalservice/api/profiles} was therefore told the new resource lives at
 * {@code https://professional.abofonsa.com/api/profiles/{id}} — the gateway prefix dropped, and a
 * 404 when followed.
 *
 * <p><b>It is item 31's defect exactly, one response class later.</b> Item 31 fixed the {@code Link}
 * header on the paginated reads by registering {@code ForwardedHeaderFilter}
 * ({@link net.jojoaddison.config.WebConfigurer#forwardedHeaderFilter()}), which makes
 * {@code getRequestURI()}, {@code getContextPath()}, the scheme and the host describe the request
 * the <i>caller</i> made rather than the one the gateway relayed. That filter does not reach
 * {@code Location}: it rewrites URLs only through {@code HttpServletResponse.sendRedirect}, and
 * there is no {@code sendRedirect} anywhere in {@code src/main/java}. A {@code Location} set
 * directly on the response is passed through verbatim, so item 31's fix left all eleven wrong.
 *
 * <p><b>Why this and not a filter.</b> Rewriting {@code Location} in a response wrapper would be one
 * place rather than eleven, but it would have to guess which values are meant to be origin-relative
 * and which are already absolute, and it would act on responses this service did not author. Going
 * through {@link ServletUriComponentsBuilder} instead makes each resource ask the same question it
 * was already answering — "what URL did the caller use to reach this collection?" — and get the
 * right answer, because the filter already put the forwarded values on the request. The recurrence
 * is held by an ArchUnit rule instead: {@code TechnicalStructureTest} fails a build in which any
 * class under {@code ..web.rest..} calls {@code new URI(String)}.
 *
 * <p><b>It fixes the scheme and host too</b>, which the hand-built string never carried at all. On
 * {@code professional.abofonsa.com} TLS terminates at nginx, so the emitted URL is now {@code https}
 * with the external host rather than a bare path resolved against whatever the client guessed.
 *
 * <p><b>Trust boundary.</b> This reads the same forwarded headers item 31 does, so it carries the
 * same requirement: the edge must scrub a client-supplied {@code X-Forwarded-Prefix}. That is done
 * in {@code deploy/docker/proxy-headers.inc}, {@code deploy/prod-server/hc-professional-app.conf}
 * and {@code quality/host-site.conf}. Nothing new is exposed here — a forged prefix could already
 * steer the {@code Link} header — but a second reader of those headers is a second reason the scrub
 * must not be removed.
 */
public final class LocationUri {

    private LocationUri() {}

    /**
     * {@code {request URI}/{id}}, fully qualified, as the caller would have to address it.
     *
     * <p><b>Precondition: the handler is mapped at the collection root</b> — {@code @PostMapping}
     * with no path, or {@code @PostMapping("")}, under a class-level {@code @RequestMapping}. All
     * eleven callers are, which is why the strings this replaced were exact reconstructions of the
     * request URI. A {@code POST} mapped at a sub-path ({@code @PostMapping("/bulk")}) would get
     * {@code /api/things/bulk/{id}} from this and must build its own URI from
     * {@link ServletUriComponentsBuilder#fromCurrentContextPath()} instead.
     *
     * <p>{@code fromCurrentRequestUri()} rather than {@code fromCurrentRequest()}: the latter keeps
     * the query string, and {@code ?foo=bar} on a create has no business in the identity of the
     * thing created.
     *
     * @param id the identifier the store assigned.
     * @return an absolute URI carrying the caller's scheme, host and gateway prefix.
     */
    public static URI of(String id) {
        return ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
    }
}
