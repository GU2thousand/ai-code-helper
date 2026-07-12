package com.aicodehelper.user.api;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.user.ClientIdentityService;
import com.aicodehelper.user.GuestSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class GuestUserController {

    private final GuestSessionService sessions;
    private final ClientIdentityService identities;
    private final AppProperties properties;

    public GuestUserController(
            GuestSessionService sessions,
            ClientIdentityService identities,
            AppProperties properties
    ) {
        this.sessions = sessions;
        this.identities = identities;
        this.properties = properties;
    }

    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestUserResponse createGuest(
            @Valid @RequestBody(required = false) CreateGuestRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        String displayName = body == null ? null : body.displayName();
        GuestSessionService.GuestSession session = identities.currentGuestClaims(httpRequest)
                .map(claims -> sessions.renew(claims.userId(), displayName, claims.displayName()))
                .orElseGet(() -> sessions.create(displayName));
        identities.addGuestCookie(response, session.token());
        String exposedToken = properties.getSecurity().isExposeTokenInResponse() ? session.token() : null;
        return new GuestUserResponse(
                session.userId(),
                session.displayName(),
                session.expiresAt(),
                "HttpOnly cookie",
                exposedToken
        );
    }

    @GetMapping("/verify")
    public VerifyGuestResponse verify(
            @RequestParam UUID userId,
            HttpServletRequest request
    ) {
        return new VerifyGuestResponse(userId, identities.verifyGuest(userId, request));
    }

    @PostMapping("/verify")
    public VerifyGuestResponse verify(
            @Valid @RequestBody VerifyGuestRequest body,
            HttpServletRequest request
    ) {
        return new VerifyGuestResponse(body.userId(), identities.verifyGuest(body.userId(), request));
    }
}
