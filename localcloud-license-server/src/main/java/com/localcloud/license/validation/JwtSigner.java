package com.localcloud.license.validation;

import io.jsonwebtoken.Jwts;

import java.security.PrivateKey;
import java.util.Date;

public final class JwtSigner {

    private final PrivateKey privateKey;

    public JwtSigner(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * Signs a license validation JWT.
     * Claims: iss=localcloud-license, sub=email, tier, device_id, exp=expiresEpoch
     */
    public String sign(String tier, String email, String deviceId, long expiresEpoch) {
        return Jwts.builder()
            .issuer("localcloud-license")
            .subject(email != null ? email : "unknown")
            .claim("tier", tier)
            .claim("device_id", deviceId != null ? deviceId : "")
            .expiration(new Date(expiresEpoch * 1000L))
            .signWith(privateKey)
            .compact();
    }
}
