package cn.apisium.nekomaid.http;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Minimal DER (ASN.1) support — just enough to read tag-length-value headers and to encode the
 * structures this plugin builds: wrapped private keys and a self-signed certificate.
 *
 * <p>Deliberately dependency-free. BouncyCastle would do this more comfortably, but it cannot be
 * relied upon at runtime: Netty looks it up reflectively, and inside a Paper plugin class loader
 * Netty resolves from the server while an embedded copy stays invisible to it.</p>
 */
final class Der {
    /** DER for INTEGER 0, which several structures need as a fixed field. */
    static final byte[] INTEGER_ZERO = {0x02, 0x01, 0x00};

    private static final ThreadLocal<SimpleDateFormat> UTC_TIME =
            ThreadLocal.withInitial(() -> {
                SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format;
            });

    private Der() { }

    /** A decoded tag-length-value header. */
    record Tlv(int tag, int offset, int length) {
        int end() { return offset + length; }
    }

    /** Reads one TLV header at {@code offset}. Content starts at {@link Tlv#offset}. */
    static Tlv read(byte[] buffer, int offset) {
        if (offset + 2 > buffer.length) throw new IllegalArgumentException("Truncated DER");
        int tag = buffer[offset] & 0xFF;
        int first = buffer[offset + 1] & 0xFF;
        if ((first & 0x80) == 0) return new Tlv(tag, offset + 2, first);
        int count = first & 0x7F;
        if (count < 1 || count > 4 || offset + 2 + count > buffer.length) {
            throw new IllegalArgumentException("Unsupported DER length");
        }
        int length = 0;
        for (int i = 0; i < count; i++) length = (length << 8) | (buffer[offset + 2 + i] & 0xFF);
        return new Tlv(tag, offset + 2 + count, length);
    }

    static byte[] sequence(byte[]... parts) { return tagged(0x30, concat(parts)); }

    static byte[] set(byte[]... parts) { return tagged(0x31, concat(parts)); }

    static byte[] octetString(byte[] content) { return tagged(0x04, content); }

    static byte[] utf8String(String value) {
        return tagged(0x0C, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] asciiString(String value) {
        return tagged(0x16, value.getBytes(StandardCharsets.US_ASCII));
    }

    /** BIT STRING with no unused bits, as used for public keys and signatures. */
    static byte[] bitString(byte[] content) {
        return tagged(0x03, concat(new byte[]{0}, content));
    }

    /** UTCTime in the {@code yyMMddHHmmssZ} form certificates use. */
    static byte[] utcTime(Date date) {
        return tagged(0x17, UTC_TIME.get().format(date).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] integer(BigInteger value) { return tagged(0x02, value.toByteArray()); }

    static byte[] tagged(int tag, byte[] content) {
        byte[] length = encodeLength(content.length);
        byte[] result = new byte[1 + length.length + content.length];
        result[0] = (byte) tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(content, 0, result, 1 + length.length, content.length);
        return result;
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] result = new byte[total];
        int position = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, position, part.length);
            position += part.length;
        }
        return result;
    }

    private static byte[] encodeLength(int length) {
        if (length < 0x80) return new byte[]{(byte) length};
        int bytes = 0;
        for (int value = length; value > 0; value >>= 8) bytes++;
        byte[] result = new byte[1 + bytes];
        result[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) result[bytes - i] = (byte) (length >> (8 * i));
        return result;
    }
}
