package cn.apisium.nekomaid.http;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates a self-signed certificate so that a fresh install serves HTTPS without the operator
 * having to obtain anything. Browsers show the usual "not trusted" warning; the panel itself still
 * authenticates every connection with a token plus a one-time password.
 *
 * <p>The certificate is an ECDSA (secp256r1) certificate valid for a year, and it always carries a
 * Subject Alternative Name — browsers have ignored the Common Name for host matching since 2021,
 * so a certificate without SAN would be rejected outright instead of merely being untrusted.</p>
 */
final class SelfSignedTls {
    /** OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256). For ECDSA the parameters must be absent. */
    private static final byte[] SHA256_WITH_ECDSA =
            {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    /** OID 2.5.4.3 (commonName). */
    private static final byte[] OID_COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};
    /** OID 2.5.29.17 (subjectAltName). */
    private static final byte[] OID_SUBJECT_ALT_NAME = {0x06, 0x03, 0x55, 0x1D, 0x11};

    /** A generated key pair together with its matching certificate. */
    record Material(PrivateKey key,
                    X509Certificate certificate) { }

    private SelfSignedTls() { }

    /**
     * Generates a self-signed certificate for the given host name — an IP literal or a DNS name;
     * {@code null} falls back to "NekoMaid".
     */
    static Material generate(String hostname) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();

        String subject = hostname == null || hostname.isBlank() ? "NekoMaid" : hostname.trim();
        byte[] distinguishedName = Der.sequence(
                Der.set(Der.sequence(OID_COMMON_NAME, Der.utf8String(subject))));
        long now = System.currentTimeMillis();
        byte[] signatureAlgorithm = Der.sequence(SHA256_WITH_ECDSA);

        byte[] tbsCertificate = Der.sequence(
                Der.tagged(0xA0, Der.integer(BigInteger.valueOf(2))),      // version v3
                Der.integer(BigInteger.valueOf(now)),                      // serialNumber
                signatureAlgorithm,
                distinguishedName,                                          // issuer
                Der.sequence(
                        Der.utcTime(new Date(now - 86_400_000L)),
                        Der.utcTime(new Date(now + 365L * 86_400_000L))),
                distinguishedName,                                          // subject
                keyPair.getPublic().getEncoded(),                           // SubjectPublicKeyInfo
                Der.tagged(0xA3, Der.sequence(subjectAltNameExtension(subject))));

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsCertificate);
        byte[] certificate = Der.sequence(tbsCertificate, signatureAlgorithm,
                Der.bitString(signer.sign()));

        X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
        return new Material(keyPair.getPrivate(), x509);
    }

    /**
     * Encodes {@code Extension { subjectAltName, OCTET STRING { GeneralNames } }} listing the
     * subject itself plus localhost and the loopback address, so both a host name and a direct IP
     * visit match.
     */
    private static byte[] subjectAltNameExtension(String subject) {
        List<byte[]> names = new ArrayList<>();
        names.add(Der.tagged(0x82, "localhost".getBytes(StandardCharsets.US_ASCII))); // dNSName
        names.add(Der.tagged(0x87, new byte[]{127, 0, 0, 1}));                        // iPAddress
        byte[] literal = literalAddress(subject);
        if (literal != null) {
            names.add(Der.tagged(0x87, literal));
        } else if (!"localhost".equalsIgnoreCase(subject)) {
            names.add(Der.tagged(0x82, subject.getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] generalNames = Der.sequence(names.toArray(new byte[0][]));
        return Der.sequence(OID_SUBJECT_ALT_NAME, Der.octetString(generalNames));
    }

    /** Returns the 4- or 16-byte form of an IP literal, or {@code null} if it is a name. */
    private static byte[] literalAddress(String subject) {
        // Only literal addresses are accepted here; getByName would also resolve DNS names, which
        // must be encoded as dNSName instead.
        if (!subject.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(subject).getAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
