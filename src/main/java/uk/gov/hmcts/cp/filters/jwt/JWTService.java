package uk.gov.hmcts.cp.filters.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
@Slf4j
public class JWTService {

    public static final String USER = "testUser";
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final String SCOPE = "scope";
    private final String secretKey;

    public JWTService(@Value("${jwt.secretKey}") final String secretKey){
        this.secretKey = secretKey;
    }

    public AuthDetails extract(final String token) throws InvalidJWTException {
        try {
            final Claims claims = Jwts.parser()
                    .verifyWith(getSecretSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            final String userName = claims.getSubject();
            final String scope = claims.get(SCOPE).toString();
            return new AuthDetails(userName, scope);
        } catch (SignatureException ex) {
            log.atError().log("Invalid signature/claims", ex);
            throw new InvalidJWTException("Invalid signature:" + ex.getMessage());
        } catch (ExpiredJwtException ex) {
            log.atError().log("Expired tokens", ex);
            throw new InvalidJWTException("Expired tokens:" + ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.atError().log("Unsupported token", ex);
            throw new InvalidJWTException("Unsupported token:" + ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.atError().log("Malformed token", ex);
            throw new InvalidJWTException("Malformed token:" + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.atError().log("JWT token is empty", ex);
            throw new InvalidJWTException("JWT token is empty:" + ex.getMessage());
        } catch (Exception ex) {
            log.atError().log("Could not verify JWT token integrity", ex);
            throw new InvalidJWTException("Could not validate JWT:" + ex.getMessage());
        }
    }

    public String createToken() {
        return Jwts.builder()
                .subject(USER)
                .issuedAt(Date.from(Instant.now()))
                .claim(SCOPE, "read write")
                .expiration(expiryDateAfterOneHour())
                .signWith(getSecretSigningKey())
                .compact();
    }

    private SecretKey getSecretSigningKey() {
        final byte[] keyBytes = Decoders.BASE64URL.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private Date expiryDateAfterOneHour() {
        return Date.from(Instant.now().plus(ONE_HOUR));
    }

}
