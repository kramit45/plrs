package com.plrs.infrastructure.security.jwt;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Supplies the RSA key pair used to sign and verify PLRS JWTs. Three
 * paths, resolved at construction time:
 *
 * <ol>
 *   <li>If both {@code plrs.jwt.private-key-pem} and {@code public-key-pem}
 *       are supplied, parse them as PKCS#8 / X.509 PEM blocks.
 *   <li>Else, if {@code plrs.jwt.generate-if-missing} is {@code true},
 *       synthesise a fresh 2048-bit keypair at startup and log a warning —
 *       acceptable for local development, never for production.
 *   <li>Else, throw so startup fails loudly rather than issuing JWTs that
 *       no verifier can trust across restarts.
 * </ol>
 *
 * <p>{@link #keyId()} returns a SHA-256 hex thumbprint of the public key's
 * DER encoding — stable for a given key and usable as the JWT {@code kid}
 * header / future JWKS entry.
 *
 * <p>Traces to: §7 (JWT RS256).
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public JwtKeyProvider(JwtProperties properties) {
        KeyPair keyPair = resolveKeyPair(properties);
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();
        this.keyId = computeKeyId(publicKey);
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String keyId() {
        return keyId;
    }

    private static KeyPair resolveKeyPair(JwtProperties properties) {
        if (properties.hasKeyMaterial()) {
            try {
                RSAPrivateKey priv = parsePrivateKey(properties.privateKeyPem());
                RSAPublicKey pub = parsePublicKey(properties.publicKeyPem());
                return new KeyPair(pub, priv);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IllegalStateException("plrs.jwt: failed to parse RSA PEM material", e);
            }
        }
        if (!properties.generateIfMissing()) {
            throw new IllegalStateException(
                    "plrs.jwt: no PEM material and generate-if-missing=false");
        }
        log.warn(
                "plrs.jwt: no PEM keys supplied — generating an ephemeral RSA-2048 keypair for"
                        + " this JVM. Tokens will not verify across restarts. Set"
                        + " PLRS_JWT_PRIVATE_KEY_PEM / PLRS_JWT_PUBLIC_KEY_PEM and"
                        + " PLRS_JWT_GENERATE_IF_MISSING=false in any non-dev environment.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA KeyPairGenerator unavailable", e);
        }
    }

    private static RSAPrivateKey parsePrivateKey(String pem)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] decoded = decodePemBody(pem);
        return (RSAPrivateKey)
                KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private static RSAPublicKey parsePublicKey(String pem)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] decoded = decodePemBody(pem);
        return (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    private static byte[] decodePemBody(String pem) {
        String body =
                pem.replaceAll("-----BEGIN [^-]+-----", "")
                        .replaceAll("-----END [^-]+-----", "")
                        .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String computeKeyId(RSAPublicKey publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}
