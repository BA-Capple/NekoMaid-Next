package cn.apisium.nekomaid.http;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a PEM certificate chain and private key using the JDK alone.
 *
 * <p>Netty's own PEM handling cannot be relied upon here. It understands PKCS#8 only, and its
 * BouncyCastle fallback requires BouncyCastle to be visible to <em>the same class loader as
 * Netty</em> — which is not the case inside a Paper plugin class loader, where Netty resolves
 * from the server while an embedded copy stays invisible to it. openssl (and therefore nginx)
 * normally writes EC keys in SEC1 form ({@code BEGIN EC PRIVATE KEY}), so the key is decoded and
 * converted to PKCS#8 here with plain JDK APIs instead.</p>
 *
 * <p>Supported PEM types: {@code PRIVATE KEY} (PKCS#8), {@code EC PRIVATE KEY} (SEC1) and
 * {@code RSA PRIVATE KEY} (PKCS#1).</p>
 */
final class TlsMaterials {
    /** DER encoding of OID 1.2.840.10045.2.1 (id-ecPublicKey). */
    private static final byte[] OID_EC_PUBLIC_KEY =
            {0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01};
    /** AlgorithmIdentifier for rsaEncryption: OID 1.2.840.113549.1.1.1 plus its NULL parameter. */
    private static final byte[] RSA_ALGORITHM =
            {0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    private TlsMaterials() { }

    /** Builds the server TLS context from a PEM certificate chain and a PEM private key. */
    static SslContext load(File certificateChain, File privateKey) throws Exception {
        X509Certificate[] chain = readCertificateChain(certificateChain);
        PrivateKey key = readPrivateKey(privateKey);
        return SslContextBuilder.forServer(key, chain).build();
    }

    /** Reads every certificate in the PEM file, leaf first. */
    private static X509Certificate[] readCertificateChain(File file) throws Exception {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (Certificate certificate : factory.generateCertificates(in)) {
                chain.add((X509Certificate) certificate);
            }
            if (chain.isEmpty()) throw new IllegalArgumentException("No certificate found in " + file);
            return chain.toArray(new X509Certificate[0]);
        }
    }

    private static PrivateKey readPrivateKey(File file) throws Exception {
        Matcher matcher = PEM_BLOCK.matcher(
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII));
        if (!matcher.find()) throw new IllegalArgumentException("No PEM block found in " + file);
        String type = matcher.group(1).trim();
        byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));

        byte[] pkcs8;
        String[] algorithms;
        switch (type) {
            case "PRIVATE KEY" -> {           // already PKCS#8
                pkcs8 = der;
                algorithms = new String[]{"EC", "RSA"};
            }
            case "EC PRIVATE KEY" -> {        // SEC1, what openssl writes for EC keys
                pkcs8 = sec1ToPkcs8(der);
                algorithms = new String[]{"EC"};
            }
            case "RSA PRIVATE KEY" -> {       // PKCS#1
                pkcs8 = pkcs1ToPkcs8(der);
                algorithms = new String[]{"RSA"};
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported PEM block in " + file + ": " + type);
        }

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        Exception failure = null;
        for (String algorithm : algorithms) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception e) {
                failure = e;
            }
        }
        throw new IllegalArgumentException("Invalid " + type + " block in " + file, failure);
    }

    /**
     * Wraps a SEC1 {@code ECPrivateKey} into a PKCS#8 {@code PrivateKeyInfo}. The inner structure
     * is copied verbatim; only the algorithm identifier has to be lifted out of it, because the
     * named curve is mandatory there.
     */
    private static byte[] sec1ToPkcs8(byte[] sec1) {
        byte[] namedCurve = findNamedCurve(sec1);
        byte[] algorithm = Der.sequence(OID_EC_PUBLIC_KEY, namedCurve);
        return Der.sequence(Der.INTEGER_ZERO, algorithm, Der.octetString(sec1));
    }

    /** Wraps a PKCS#1 {@code RSAPrivateKey} into a PKCS#8 {@code PrivateKeyInfo}. */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        return Der.sequence(Der.INTEGER_ZERO, RSA_ALGORITHM, Der.octetString(pkcs1));
    }

    /**
     * Returns the {@code [0] parameters} element (the named-curve OID, as a complete DER value) of
     * a SEC1 private key: {@code SEQUENCE { INTEGER, OCTET STRING, [0] { OID }, [1] ... }}.
     */
    private static byte[] findNamedCurve(byte[] sec1) {
        Der.Tlv outer = Der.read(sec1, 0);
        int position = outer.offset();
        while (position < outer.end()) {
            Der.Tlv element = Der.read(sec1, position);
            if (element.tag() == 0xA0) {                 // [0] EXPLICIT ECParameters
                Der.Tlv oid = Der.read(sec1, element.offset());
                if (oid.tag() == 0x06) return Arrays.copyOfRange(sec1, element.offset(), oid.end());
            }
            position = element.end();
        }
        throw new IllegalArgumentException("EC private key carries no named curve");
    }
}
