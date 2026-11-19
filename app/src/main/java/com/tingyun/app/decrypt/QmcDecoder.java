package com.tingyun.app.decrypt;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class QmcDecoder {

    private static final byte[] V1_STATIC_KEY = bytesOf(
            0xC3, 0x4A, 0xD6, 0xCA, 0x90, 0x67, 0xF7, 0x52, 0xD8, 0xA1, 0x66, 0x62, 0x9F, 0x5B, 0x09, 0x00,
            0xC3, 0x5E, 0x95, 0x23, 0x9F, 0x13, 0x11, 0x7E, 0xD8, 0x92, 0x3F, 0xBC, 0x90, 0xBB, 0x74, 0x0E,
            0xC3, 0x47, 0x74, 0x3D, 0x90, 0xAA, 0x3F, 0x51, 0xD8, 0xF4, 0x11, 0x84, 0x9F, 0xDE, 0x95, 0x1D,
            0xC3, 0xC6, 0x09, 0xD5, 0x9F, 0xFA, 0x66, 0xF9, 0xD8, 0xF0, 0xF7, 0xA0, 0x90, 0xA1, 0xD6, 0xF3,
            0xC3, 0xF3, 0xD6, 0xA1, 0x90, 0xA0, 0xF7, 0xF0, 0xD8, 0xF9, 0x66, 0xFA, 0x9F, 0xD5, 0x09, 0xC6,
            0xC3, 0x1D, 0x95, 0xDE, 0x9F, 0x84, 0x11, 0xF4, 0xD8, 0x51, 0x3F, 0xAA, 0x90, 0x3D, 0x74, 0x47,
            0xC3, 0x0E, 0x74, 0xBB, 0x90, 0xBC, 0x3F, 0x92, 0xD8, 0x7E, 0x11, 0x13, 0x9F, 0x23, 0x95, 0x5E,
            0xC3, 0x00, 0x09, 0x5B, 0x9F, 0x62, 0x66, 0xA1, 0xD8, 0x52, 0xF7, 0x67, 0x90, 0xCA, 0xD6, 0x4A);

    private static final Set<String> V1_EXTS = new HashSet<>(Arrays.asList(
            "tkm", "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma",
            "666c6163", "6d7033", "6f6767", "6d3461", "776176"));

    private static final Set<String> V2_EXTS = new HashSet<>(Arrays.asList(
            "mflac", "mflac0", "mgg", "mgg0", "mgg1", "mggl", "mmp4",
            "qmcflac", "qmcogg", "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8"));

    private static final int MAX_EKEY_LEN = 0x500;
    private static final int MUSICEX_BLOCK = 0xC0;

    private static final byte[] EKEY_V2_PREFIX = android.util.Base64.encodeToString(
            "QQMusic EncV2,Key:".getBytes(Charset.forName("US-ASCII")),
            android.util.Base64.NO_WRAP).getBytes(Charset.forName("US-ASCII"));

    private static final byte[] EKEY_V2_KEY1 = bytesOf(
            0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
            0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28);
    private static final byte[] EKEY_V2_KEY2 = bytesOf(
            0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
            0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54);

    private static final byte[] SIMPLE_KEY = simpleKey8();

    private QmcDecoder() {
    }

    public static final class Result {
        public byte[] audio;
        public String container = "bin";
        
        public String source = "";
        
        public String mid = "";
    }

    public static String extOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean supports(String fileName) {
        String e = extOf(fileName);
        return V1_EXTS.contains(e) || V2_EXTS.contains(e);
    }

    public static boolean isV1(String fileName) {
        return V1_EXTS.contains(extOf(fileName));
    }

    public static Result decode(byte[] data, String fileName, String ekey)
            throws Cipher.DecryptException {
        if (data == null || data.length < 16) {
            throw new Cipher.DecryptException("文件太小");
        }
        String ext = extOf(fileName);
        Result r = new Result();

        if (V1_EXTS.contains(ext)) {
            r.audio = Cipher.qmc1Transform(data, V1_STATIC_KEY, 0);
            r.container = Cipher.sniffContainer(head(r.audio, 64));
            r.source = "qmc-v1";
            return r;
        }

        if (!V2_EXTS.contains(ext)) {
            throw new Cipher.DecryptException("不支持的格式");
        }

        Footer footer = null;
        try {
            int tailLen = Math.min(1024, data.length);
            footer = parseFooter(Arrays.copyOfRange(data, data.length - tailLen, data.length));
        } catch (Cipher.DecryptException e) {
            footer = null;
        }

        if (footer == null) {
            
            byte[] probe = Cipher.qmc1Transform(head(data, 64), V1_STATIC_KEY, 0);
            if (!"bin".equals(Cipher.sniffContainer(probe))) {
                r.audio = Cipher.qmc1Transform(data, V1_STATIC_KEY, 0);
                r.container = Cipher.sniffContainer(head(r.audio, 64));
                r.source = "qmc-v1";
                return r;
            }
            throw new Cipher.DecryptException("未找到尾包");
        }

        r.mid = footer.mid == null ? "" : footer.mid;

        String useEkey = footer.ekey;
        if (useEkey == null || useEkey.length() == 0) useEkey = ekey;
        if (useEkey == null || useEkey.length() == 0) {
            
            throw new Cipher.DecryptException("no-ekey:" + footer.kind);
        }

        byte[] master = deriveMasterKey(useEkey.getBytes(Charset.forName("ISO-8859-1")));
        Cipher.Stream stream = Cipher.makeStream(master);

        int bodyLen = data.length - footer.size;
        if (bodyLen <= 0) throw new Cipher.DecryptException("尾包长度异常");
        byte[] body = Arrays.copyOf(data, bodyLen);
        r.audio = stream.decrypt(body, 0);
        r.container = Cipher.sniffContainer(head(r.audio, 64));
        r.source = "qmc-v2:" + footer.kind;
        return r;
    }

    private static final class Footer {
        int size;
        String ekey;
        String kind = "";
        String mid = "";
    }

    private static Footer parseFooter(byte[] tail) throws Cipher.DecryptException {
        if (tail.length < 8) return null;

        if (endsWith(tail, "STag")) {
            byte[] body = Arrays.copyOf(tail, tail.length - 4);
            int payloadLen = Cipher.u32be(body, body.length - 4);
            int start = body.length - 4 - payloadLen;
            if (payloadLen <= 0 || start < 0) throw new Cipher.DecryptException("STag 长度不一致");
            String[] parts = new String(Arrays.copyOfRange(body, start, start + payloadLen),
                    Charset.forName("UTF-8")).split(",");
            if (parts.length != 3 || !"2".equals(parts[1])) {
                throw new Cipher.DecryptException("STag 内容非法");
            }
            Footer f = new Footer();
            f.size = payloadLen + 8;
            f.kind = "STag";
            f.mid = parts[2];
            return f;
        }

        if (endsWith(tail, "QTag")) {
            byte[] body = Arrays.copyOf(tail, tail.length - 4);
            int payloadLen = Cipher.u32be(body, body.length - 4);
            int start = body.length - 4 - payloadLen;
            if (payloadLen <= 0 || start < 0) throw new Cipher.DecryptException("QTag 长度不一致");
            String[] parts = new String(Arrays.copyOfRange(body, start, start + payloadLen),
                    Charset.forName("UTF-8")).split(",");
            if (parts.length != 3 || !"2".equals(parts[2])) {
                throw new Cipher.DecryptException("QTag 内容非法");
            }
            Footer f = new Footer();
            f.ekey = parts[0];
            f.size = payloadLen + 8;
            f.kind = "QTag";
            return f;
        }

        if (endsWith(tail, "musicex\u0000")) {
            byte[] payload = Arrays.copyOf(tail, tail.length - 8);
            if (payload.length < 8) throw new Cipher.DecryptException("MusicEx 过短");
            int version = Cipher.u32le(payload, payload.length - 4);
            if (version != 1) throw new Cipher.DecryptException("MusicEx 版本不支持");
            byte[] inner = Arrays.copyOf(payload, payload.length - 4);
            int payloadLen = Cipher.u32le(inner, inner.length - 4);
            if (payloadLen != MUSICEX_BLOCK) {
                throw new Cipher.DecryptException("MusicEx 长度非法");
            }
            byte[] src = Arrays.copyOf(inner, inner.length - 4);
            int take = payloadLen - 0x10;
            if (src.length < take) throw new Cipher.DecryptException("MusicEx 内容过短");
            byte[] block = Arrays.copyOfRange(src, src.length - take, src.length);
            Footer f = new Footer();
            f.size = MUSICEX_BLOCK + 12;
            f.kind = "MusicEx";
            f.mid = readUtf16Le(block, 12, 60);
            return f;
        }

        byte[] payload = Arrays.copyOf(tail, tail.length - 4);
        int payloadLen = Cipher.u32le(tail, tail.length - 4);
        if (payloadLen <= 0 || payloadLen > MAX_EKEY_LEN) return null;
        if (payload.length < payloadLen) throw new Cipher.DecryptException("PcV1Legacy 长度不一致");
        byte[] ekeyBytes = Arrays.copyOfRange(payload, payload.length - payloadLen, payload.length);
        int zero = indexOfZero(ekeyBytes);
        if (zero == 0) return null;
        if (zero > 0) ekeyBytes = Arrays.copyOf(ekeyBytes, zero);
        if (!isBase64Text(ekeyBytes)) throw new Cipher.DecryptException("PcV1Legacy EKey 非法");

        Footer f = new Footer();
        f.ekey = new String(ekeyBytes, Charset.forName("ISO-8859-1"));
        f.size = payloadLen + 4;
        f.kind = "PcV1Legacy";
        return f;
    }

    public static byte[] deriveMasterKey(byte[] ekey) throws Cipher.DecryptException {
        if (startsWith(ekey, EKEY_V2_PREFIX)) {
            byte[] payload = android.util.Base64.decode(
                    Arrays.copyOfRange(ekey, EKEY_V2_PREFIX.length, ekey.length),
                    android.util.Base64.DEFAULT);
            payload = Cipher.teaCbcDecrypt(payload, EKEY_V2_KEY1);
            payload = Cipher.teaCbcDecrypt(payload, EKEY_V2_KEY2);
            int zero = indexOfZero(payload);
            if (zero >= 0) payload = Arrays.copyOf(payload, zero);
            return ekeyV1(payload);
        }
        return ekeyV1(ekey);
    }

    private static byte[] ekeyV1(byte[] ekey) throws Cipher.DecryptException {
        byte[] decoded = android.util.Base64.decode(ekey, android.util.Base64.DEFAULT);
        if (decoded == null || decoded.length < 8) {
            throw new Cipher.DecryptException("EKey 解码后不足 8 字节");
        }
        byte[] header = Arrays.copyOf(decoded, 8);
        byte[] cipher = Arrays.copyOfRange(decoded, 8, decoded.length);

        byte[] teaKey = new byte[16];
        for (int i = 0; i < 8; i++) {
            teaKey[i * 2] = SIMPLE_KEY[i];
            teaKey[i * 2 + 1] = header[i];
        }
        byte[] body = Cipher.teaCbcDecrypt(cipher, teaKey);

        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    private static byte[] simpleKey8() {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            float t = 106.0f + f32(i * 0.1f);
            double v = Math.abs(Math.tan(t));
            int iv = (int) f32((float) v * 100.0f);
            if (iv < 0) iv = 0;
            if (iv > 255) iv = 255;
            out[i] = (byte) iv;
        }
        return out;
    }

    private static float f32(float x) {
        return x;
    }

    private static byte[] head(byte[] d, int n) {
        return d.length <= n ? d : Arrays.copyOf(d, n);
    }

    private static boolean endsWith(byte[] data, String suffix) {
        byte[] s = suffix.getBytes(Charset.forName("ISO-8859-1"));
        if (data.length < s.length) return false;
        for (int i = 0; i < s.length; i++) {
            if (data[data.length - s.length + i] != s[i]) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static int indexOfZero(byte[] d) {
        for (int i = 0; i < d.length; i++) {
            if (d[i] == 0) return i;
        }
        return -1;
    }

    private static boolean isBase64Text(byte[] s) {
        if (s.length == 0) return false;
        for (byte b : s) {
            boolean ok = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '+' || b == '/' || b == '=';
            if (!ok) return false;
        }
        return true;
    }

    private static String readUtf16Le(byte[] d, int from, int max) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(d.length - 1, from + max);
        for (int i = from; i < end; i += 2) {
            int lo = d[i] & 0xFF;
            int hi = d[i + 1] & 0xFF;
            if (lo == 0 && hi == 0) break;
            if (hi == 0 && lo > 0 && lo < 128) {
                sb.append((char) lo);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static byte[] bytesOf(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
