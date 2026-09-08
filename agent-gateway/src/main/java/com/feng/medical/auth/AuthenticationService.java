package com.feng.medical.auth;

import com.feng.medical.security.AccessTokenClaims;
import com.feng.medical.security.AccessTokenService;
import com.feng.medical.security.AuthenticatedUser;
import com.feng.medical.user.UserAccount;
import com.feng.medical.user.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final String DUMMY_PASSWORD_HASH = "$2a$12$7RIheLzid1xGFRw4zZXJ0euyBdCncEp48Qug3SGwrip6ph1l4VZt2";

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final Clock clock;
    private final Duration refreshTtl;

    @Autowired
    public AuthenticationService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokens,
            Clock clock,
            RefreshTokenProperties refreshTokenProperties) {
        this(users, refreshTokens, passwordEncoder, accessTokens, clock, Duration.ofSeconds(refreshTokenProperties.ttlSeconds()));
    }

    public AuthenticationService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokens,
            Clock clock,
            Duration refreshTtl) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public AuthTokens login(String username, String password) {
        UserAccount account = users.findByUsername(username).orElse(null);
        if (account == null) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            throw new InvalidCredentialsException();
        }
        if (!account.active() || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return issue(account);
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshTokenRecord current = refreshTokens.findByHash(hashRefreshToken(rawRefreshToken))
                .filter(token -> token.isUsableAt(now))
                .orElseThrow(InvalidRefreshTokenException::new);
        UserAccount account = users.findById(current.userId())
                .filter(UserAccount::active)
                .orElseThrow(InvalidRefreshTokenException::new);
        UUID replacementId = UUID.randomUUID();
        if (!refreshTokens.revokeIfActive(current.id(), now, replacementId)) {
            throw new InvalidRefreshTokenException();
        }
        return issue(account, replacementId);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByHash(hashRefreshToken(rawRefreshToken))
                .ifPresent(token -> refreshTokens.revokeIfActive(token.id(), clock.instant(), null));
    }

    public AuthenticatedUser authenticateAccessToken(String rawAccessToken) {
        AccessTokenClaims claims = accessTokens.verify(rawAccessToken);
        UserAccount account = users.findById(claims.userId())
                .filter(UserAccount::active)
                .filter(found -> found.authVersion() == claims.authVersion())
                .orElseThrow(() -> new com.feng.medical.security.InvalidAccessTokenException("access token is no longer valid"));
        return new AuthenticatedUser(account.id(), account.username(), account.role(), account.authVersion());
    }

    String hashRefreshToken(String rawRefreshToken) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawRefreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AuthTokens issue(UserAccount account) {
        return issue(account, UUID.randomUUID());
    }

    private AuthTokens issue(UserAccount account, UUID refreshTokenId) {
        Instant now = clock.instant();
        String rawRefreshToken = newRawRefreshToken();
        refreshTokens.save(new RefreshTokenRecord(
                refreshTokenId,
                account.id(),
                hashRefreshToken(rawRefreshToken),
                now.plus(refreshTtl),
                null,
                null));
        AuthenticatedUser user = new AuthenticatedUser(account.id(), account.username(), account.role(), account.authVersion());
        String accessToken = accessTokens.issue(user);
        AccessTokenClaims claims = accessTokens.verify(accessToken);
        return new AuthTokens(accessToken, rawRefreshToken, account.id(), account.username(), account.role(), claims.expiresAt());
    }

    private String newRawRefreshToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }
}
