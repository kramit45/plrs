package com.plrs.infrastructure.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JwtKeyProviderTest {

    private static KeyPair keypairA;
    private static KeyPair keypairB;

    @BeforeAll
    static void generateKeypairs() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keypairA = generator.generateKeyPair();
        keypairB = generator.generateKeyPair();
    }

    private static JwtProperties propertiesWith(
            String privatePem, String publicPem, boolean generateIfMissing) {
        return new JwtProperties(
                Duration.ofHours(2),
                Duration.ofDays(30),
                "plrs",
                "plrs-web",
                privatePem,
                publicPem,
                generateIfMissing);
    }

    private static String toPem(byte[] encoded, String label) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----";
    }

    @Test
    void generatesEphemeralKeypairWhenPemsAbsentAndGenerationAllowed() {
        JwtKeyProvider provider = new JwtKeyProvider(propertiesWith(null, null, true));

        assertThat(provider.privateKey()).isInstanceOf(RSAPrivateKey.class);
        assertThat(provider.publicKey()).isInstanceOf(RSAPublicKey.class);
        assertThat(provider.keyId()).isNotBlank();
    }

    @Test
    void loadsKeypairFromPemStringsWhenSupplied() {
        String privatePem = toPem(keypairA.getPrivate().getEncoded(), "PRIVATE KEY");
        String publicPem = toPem(keypairA.getPublic().getEncoded(), "PUBLIC KEY");

        JwtKeyProvider provider =
                new JwtKeyProvider(propertiesWith(privatePem, publicPem, false));

        assertThat(provider.publicKey().getEncoded())
                .isEqualTo(keypairA.getPublic().getEncoded());
        assertThat(provider.privateKey().getEncoded())
                .isEqualTo(keypairA.getPrivate().getEncoded());
    }

    @Test
    void throwsWhenGenerationDisabledAndPemsAbsent() {
        // JwtProperties canonical ctor fails first when generate-if-missing=false
        // is combined with missing PEMs — the invariant lives there, not in the
        // provider, so the error message points at config.
        assertThatThrownBy(() -> propertiesWith(null, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generate-if-missing=false");
    }

    @Test
    void keyIdIsStableAcrossCallsForTheSameKey() {
        String privatePem = toPem(keypairA.getPrivate().getEncoded(), "PRIVATE KEY");
        String publicPem = toPem(keypairA.getPublic().getEncoded(), "PUBLIC KEY");
        JwtKeyProvider provider =
                new JwtKeyProvider(propertiesWith(privatePem, publicPem, false));

        assertThat(provider.keyId()).isEqualTo(provider.keyId());
    }

    @Test
    void keyIdMatchesAcrossProvidersLoadingTheSameKey() {
        String privatePem = toPem(keypairA.getPrivate().getEncoded(), "PRIVATE KEY");
        String publicPem = toPem(keypairA.getPublic().getEncoded(), "PUBLIC KEY");

        JwtKeyProvider first = new JwtKeyProvider(propertiesWith(privatePem, publicPem, false));
        JwtKeyProvider second = new JwtKeyProvider(propertiesWith(privatePem, publicPem, false));

        assertThat(first.keyId()).isEqualTo(second.keyId());
    }

    @Test
    void keyIdDiffersForDifferentKeys() {
        String pemAPriv = toPem(keypairA.getPrivate().getEncoded(), "PRIVATE KEY");
        String pemAPub = toPem(keypairA.getPublic().getEncoded(), "PUBLIC KEY");
        String pemBPriv = toPem(keypairB.getPrivate().getEncoded(), "PRIVATE KEY");
        String pemBPub = toPem(keypairB.getPublic().getEncoded(), "PUBLIC KEY");

        JwtKeyProvider providerA = new JwtKeyProvider(propertiesWith(pemAPriv, pemAPub, false));
        JwtKeyProvider providerB = new JwtKeyProvider(propertiesWith(pemBPriv, pemBPub, false));

        assertThat(providerA.keyId()).isNotEqualTo(providerB.keyId());
    }

    @Test
    void badPemMaterialThrowsAtConstruction() {
        assertThatThrownBy(
                        () ->
                                new JwtKeyProvider(
                                        propertiesWith("not a pem", "also not a pem", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PEM");
    }
}
