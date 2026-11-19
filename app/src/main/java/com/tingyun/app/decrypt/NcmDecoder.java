package com.tingyun.app.decrypt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.util.Arrays;

public final class NcmDecoder {

    private static final byte[] MAGIC = "CTENFDAM".getBytes(Charset.forName("US-ASCII"));
    
    private static final byte[] CORE_KEY = bytesOf(
            0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
            0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57);
    
    private static final byte[] META_KEY = bytesOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28);
    private static final byte[] META_PREFIX =
            "163 key(Don't modify):".getBytes(Charset.forName("US-ASCII"));
    
    private static final int KEY_PREFIX_LEN = 17;

    private NcmDecoder() {
    }

    public static final class Result {
        
        public byte[] audio;
        
        public String container = "mp3";
        
        public String title = "";
        
        public String artist = "";
        
        public String album = "";
        
        public String lyric = "";
        
        public byte[] cover;
    }

    public static boolean isNcm(byte[] head) {
        if (head == null || head.length < 8) return false;
        for (int i = 0; i < 8; i++) {
            if (head[i] != MAGIC[i]) return false;
        }
        return true;
    }

    public static Result decode(byte[] data, String ekey) throws Cipher.DecryptException {
        if (data == null || data.length < 0x2C) {
            throw new Cipher.DecryptException("文件太小，不是合法的 NCM");
        }
        if (!isNcm(data)) {
            throw new Cipher.DecryptException("不是合法的 NCM 文件");
        }

        int pos = 10; 

        int keyLen = Cipher.u32le(data, pos);
        pos += 4;
        if (keyLen <= 0 || pos + keyLen > data.length) {
            throw new Cipher.DecryptException("NCM 密钥区长度非法");
        }
        byte[] keyBlob = new byte[keyLen];
        for (int i = 0; i < keyLen; i++) keyBlob[i] = (byte) (data[pos + i] ^ 0x64);
        pos += keyLen;

        byte[] keyPlain = aesEcbDecryptPadded(keyBlob, CORE_KEY);
        if (keyPlain.length <= KEY_PREFIX_LEN) {
            throw new Cipher.DecryptException("NCM 密钥区过短");
        }
        byte[] rc4Key = Arrays.copyOfRange(keyPlain, KEY_PREFIX_LEN, keyPlain.length);

        int metaLen = Cipher.u32le(data, pos);
        pos += 4;
        Result result = new Result();
        if (metaLen > 0) {
            if (pos + metaLen > data.length) {
                throw new Cipher.DecryptException("NCM 元数据区长度非法");
            }
            byte[] metaXor = new byte[metaLen];
            for (int i = 0; i < metaLen; i++) metaXor[i] = (byte) (data[pos + i] ^ 0x63);
            pos += metaLen;

            if (startsWith(metaXor, META_PREFIX)) {
                try {
                    byte[] b64 = base64Decode(Arrays.copyOfRange(metaXor, META_PREFIX.length, metaXor.length));
                    byte[] plain = aesEcbDecryptPadded(b64, META_KEY);
                    
                    String json = new String(plain, 6, plain.length - 6, Charset.forName("UTF-8"));
                    fillMeta(result, json);
                } catch (Exception e) {
                    
                    result.title = "";
                    result.artist = "";
                }
            }
        }

        pos += 5; 
        if (pos + 8 <= data.length) {
            int imageSpace = Cipher.u32le(data, pos);
            pos += 4;
            int imageSize = Cipher.u32le(data, pos);
            pos += 4;
            if (imageSize > 0 && imageSpace > 0 && pos + imageSize <= data.length) {
                result.cover = Arrays.copyOfRange(data, pos, pos + imageSize);
            }
            long next = (long) pos + (imageSpace & 0xFFFFFFFFL);
            pos = next <= data.length ? (int) next : data.length;
        }

        if (pos >= data.length) {
            throw new Cipher.DecryptException("NCM 没有音频数据");
        }
        byte[] box = Cipher.ncmStreamBytes(rc4Key);
        byte[] raw = Arrays.copyOfRange(data, pos, data.length);
        result.audio = Cipher.ncmDecryptAudio(raw, box);

        String sniff = Cipher.sniffContainer(result.audio.length > 64
                ? Arrays.copyOf(result.audio, 64) : result.audio);
        if (result.container == null || result.container.length() == 0
                || "bin".equals(result.container)) {
            result.container = "bin".equals(sniff) ? "mp3" : sniff;
        }
        return result;
    }

    private static void fillMeta(Result r, String json) {
        try {
            JSONObject o = new JSONObject(json);
            r.title = o.optString("musicName", "");
            r.album = o.optString("album", "");
            String fmt = o.optString("format", "");
            if (fmt.length() > 0) r.container = fmt.toLowerCase();

            JSONArray artists = o.optJSONArray("artist");
            if (artists != null && artists.length() > 0) {
                Object first = artists.opt(0);
                if (first instanceof JSONArray) {
                    r.artist = ((JSONArray) first).optString(0, "");
                } else if (first != null) {
                    String s = first.toString().trim();
                    if (s.startsWith("[") && s.endsWith("]")) {
                        s = s.substring(1, s.length() - 1);
                    }
                    
                    int comma = s.indexOf(',');
                    if (comma >= 0) s = s.substring(0, comma);
                    s = s.trim();
                    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                        s = s.substring(1, s.length() - 1);
                    }
                    r.artist = s.trim();
                }
            }

            String lrc = o.optString("lyric", "");
            if (lrc.length() == 0) lrc = o.optString("translatedLyric", "");
            r.lyric = lrc;
        } catch (Exception ignore) {
        }
    }

    private static byte[] aesEcbDecryptPadded(byte[] data, byte[] key)
            throws Cipher.DecryptException {
        if (data.length == 0 || data.length % 16 != 0) {
            throw new Cipher.DecryptException("AES 密文长度不是 16 的倍数");
        }
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"));
            byte[] plain = c.doFinal(data);

            int pad = plain[plain.length - 1] & 0xFF;
            if (pad >= 1 && pad <= 16 && pad <= plain.length) {
                boolean ok = true;
                for (int i = plain.length - pad; i < plain.length; i++) {
                    if ((plain[i] & 0xFF) != pad) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return Arrays.copyOf(plain, plain.length - pad);
                }
            }
            return plain;
        } catch (Exception e) {
            throw new Cipher.DecryptException("AES 解密失败");
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] base64Decode(byte[] in) {
        return android.util.Base64.decode(in, android.util.Base64.DEFAULT);
    }

    private static byte[] bytesOf(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
