package net.jojoaddison.security.jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing testing authentication token.
 */
@RestController
@RequestMapping("/api")
public class TestAuthenticationResource {

    /**
     * {@code GET  /authenticate} : check if the authentication token correctly validates
     *
     * @return ok.
     */
    @GetMapping("/authenticate")
    public String isAuthenticated() {
        return "ok";
    }
}
