package com.hcx.streamhub.auth.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.hcx.streamhub.auth.config.JwtProperties;
import com.hcx.streamhub.user.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtTokenProvider {

	private final JwtProperties jwtProperties;
	private final SecretKey secretKey;

	public JwtTokenProvider(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
		this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	public String generateToken(User user) {
		Date now = new Date();
		Date expiration = new Date(now.getTime() + jwtProperties.getExpiration());

		return Jwts.builder()
				.issuer(jwtProperties.getIssuer())
				.subject(String.valueOf(user.getId()))
				.claim("username", user.getUsername())
				.issuedAt(now)
				.expiration(expiration)
				.signWith(secretKey)
				.compact();
	}

	public AuthenticatedUser parseToken(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(secretKey)
				.requireIssuer(jwtProperties.getIssuer())
				.build()
				.parseSignedClaims(token)
				.getPayload();

		return new AuthenticatedUser(Long.valueOf(claims.getSubject()), claims.get("username", String.class));
	}

	public long getExpiration() {
		return jwtProperties.getExpiration();
	}
}
