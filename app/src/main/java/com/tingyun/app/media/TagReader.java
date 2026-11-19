package com.tingyun.app.media;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public final class TagReader {

    private TagReader() {
    }

    public static final class Tag {
        public String title = "";
        public String artist = "";
        
        public byte[] cover;
        
        public String album = "";

        public boolean isEmpty() {
            return title.length() == 0 && artist.length() == 0;
        }
    }

    public static Tag read(byte[] d, String ext) {
        Tag t = new Tag();
        if (d == null || d.length < 16) return t;
        try {
            if ("mp3".equals(ext)) {
                readId3(d, t);
            } else if ("flac".equals(ext)) {
                readFlac(d, t);
            } else if ("m4a".equals(ext) || "mp4".equals(ext) || "aac".equals(ext)) {
                readMp4(d, t);
            }
        } catch (Exception ignore) {
            
        }
        return t;
    }

    private static void readId3(byte[] d, Tag out) {
        if (!(d[0] == 'I' && d[1] == 'D' && d[2] == '3')) return;
        int ver = d[3] & 0xFF;
        int flags = d[5] & 0xFF;

        int tagSize = ((d[6] & 0x7F) << 21) | ((d[7] & 0x7F) << 14)
                | ((d[8] & 0x7F) << 7) | (d[9] & 0x7F);
        int start = 10;

        if ((flags & 0x40) != 0 && start + 4 <= d.length) {
            int extSize = ((d[start] & 0x7F) << 21) | ((d[start + 1] & 0x7F) << 14)
                    | ((d[start + 2] & 0x7F) << 7) | (d[start + 3] & 0x7F);
            start += extSize;
        }

        int end = Math.min(d.length, 10 + tagSize);
        int p = start;

        while (p + 10 <= end) {
            int idLen = (ver == 2) ? 3 : 4;

            if (d[p] == 0) break;

            String id = new String(d, p, idLen, Charset.forName("US-ASCII"));

            int bodySize;
            int headLen;
            int encFlags = 0;

            if (ver == 2) {
                bodySize = ((d[p + 3] & 0xFF) << 16) | ((d[p + 4] & 0xFF) << 8) | (d[p + 5] & 0xFF);
                headLen = 6;
            } else {
                
                if (ver == 4) {
                    bodySize = ((d[p + 4] & 0x7F) << 21) | ((d[p + 5] & 0x7F) << 14)
                            | ((d[p + 6] & 0x7F) << 7) | (d[p + 7] & 0x7F);
                } else {
                    bodySize = ((d[p + 4] & 0xFF) << 24) | ((d[p + 5] & 0xFF) << 16)
                            | ((d[p + 6] & 0xFF) << 8) | (d[p + 7] & 0xFF);
                }
                encFlags = ((d[p + 8] & 0xFF) << 8) | (d[p + 9] & 0xFF);
                headLen = 10;
            }

            if (bodySize <= 0 || p + headLen + bodySize > end) break;

            boolean isTitle = (ver == 2) ? "TT2".equals(id) : "TIT2".equals(id);
            boolean isArtist = (ver == 2) ? "TP1".equals(id) : "TPE1".equals(id);
            boolean isAlbum = (ver == 2) ? "TAL".equals(id) : "TALB".equals(id);
            
            boolean isPic = (ver == 2) ? "PIC".equals(id) : "APIC".equals(id);

            if (isPic) {
                int b = p + headLen;
                int len = bodySize;
                boolean skipPic = false;
                if (ver >= 3) {
                    if ((encFlags & 0x00C0) != 0) skipPic = true;
                    if ((flags & 0x80) != 0) skipPic = true;
                }
                if (!skipPic && len > 4) {
                    byte[] img = readApic(d, b, len);
                    
                    if (img != null && out.cover == null && img.length < 4 * 1024 * 1024) {
                        out.cover = img;
                    }
                }
            }

            if (isTitle || isArtist || isAlbum) {
                int b = p + headLen;
                int len = bodySize;

                boolean skip = false;
                if (ver >= 3) {
                    if ((encFlags & 0x00C0) != 0) skip = true;   
                    if ((encFlags & 0x0020) != 0) {               
                        b += 1;
                        len -= 1;
                    }
                    if ((flags & 0x80) != 0) {                    
                        skip = true;
                    }
                }
                if (!skip && len > 1) {
                    String v = decodeText(d, b, len);
                    if (isTitle && out.title.length() == 0) out.title = v;
                    if (isArtist && out.artist.length() == 0) out.artist = v;
                    if (isAlbum && out.album.length() == 0) out.album = v;
                }
            }

            p += headLen + bodySize;
        }
    }

    private static String decodeText(byte[] d, int off, int len) {
        int enc = d[off] & 0xFF;
        int start = off + 1;
        int n = len - 1;
        if (n <= 0) return "";

        String s;
        switch (enc) {
            case 1:
                s = utf16WithBom(d, start, n);
                break;
            case 2:
                s = new String(d, start, n, Charset.forName("UTF-16BE"));
                break;
            case 3:
                s = new String(d, start, n, Charset.forName("UTF-8"));
                break;
            default:
                s = new String(d, start, n, Charset.forName("ISO-8859-1"));
                break;
        }
        return clean(s);
    }

    private static String utf16WithBom(byte[] d, int off, int len) {
        if (len >= 2) {
            int b0 = d[off] & 0xFF, b1 = d[off + 1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return new String(d, off + 2, len - 2, Charset.forName("UTF-16LE"));
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return new String(d, off + 2, len - 2, Charset.forName("UTF-16BE"));
            }
        }
        try {
            return new String(d, off, len, "UTF-16LE");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static byte[] readApic(byte[] d, int off, int len) {
        int end = Math.min(d.length, off + len);
        if (off >= end) return null;

        int enc = d[off] & 0xFF;
        int p = off + 1;

        while (p < end && d[p] != 0) p++;
        if (p >= end) return null;
        p++;                       

        if (p >= end) return null;
        p++;

        if (enc == 1 || enc == 2) {
            
            while (p + 1 < end && !(d[p] == 0 && d[p + 1] == 0)) p += 2;
            p += 2;
        } else {
            while (p < end && d[p] != 0) p++;
            p++;
        }
        if (p >= end) return null;

        int n = end - p;
        if (n <= 0) return null;
        byte[] img = new byte[n];
        System.arraycopy(d, p, img, 0, n);
        return img;
    }

    private static void readFlac(byte[] d, Tag out) {
        if (!(d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C')) return;

        int p = 4;
        int guard = 0;
        while (p + 4 <= d.length && guard++ < 128) {
            int header = d[p] & 0xFF;
            boolean last = (header & 0x80) != 0;
            int type = header & 0x7F;
            int size = ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            int body = p + 4;

            if (body + size > d.length) break;

            if (type == 4) {
                readVorbisComment(d, body, size, out);
                
            }
            
            if (type == 6) {
                byte[] img = readFlacPicture(d, body, size);
                if (img != null && out.cover == null && img.length < 4 * 1024 * 1024) {
                    out.cover = img;
                }
            }
            if (last) break;
            p = body + size;
        }
    }

    private static void readVorbisComment(byte[] d, int off, int len, Tag out) {
        int p = off;
        int end = Math.min(d.length, off + len);

        if (p + 4 > end) return;
        int vlen = le32(d, p);
        p += 4 + vlen;
        if (p + 4 > end) return;

        int count = le32(d, p);
        p += 4;
        if (count < 0 || count > 1024) return;

        Charset utf8 = Charset.forName("UTF-8");
        for (int i = 0; i < count && p + 4 <= end; i++) {
            int clen = le32(d, p);
            p += 4;
            if (clen <= 0 || p + clen > end) break;

            String kv = new String(d, p, clen, utf8);
            p += clen;

            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).toUpperCase();
            String v = clean(kv.substring(eq + 1));

            if ("TITLE".equals(k) && out.title.length() == 0) out.title = v;
            if ("ARTIST".equals(k) && out.artist.length() == 0) out.artist = v;
        }
    }

    private static byte[] readFlacPicture(byte[] d, int off, int len) {
        int end = Math.min(d.length, off + len);
        int p = off;
        if (p + 4 > end) return null;
        p += 4;                                     

        if (p + 4 > end) return null;
        int mimeLen = be32(d, p);
        p += 4 + mimeLen;                           

        if (p + 4 > end) return null;
        int descLen = be32(d, p);
        p += 4 + descLen;                           

        p += 16;
        if (p + 4 > end) return null;
        int dataLen = be32(d, p);
        p += 4;
        if (dataLen <= 0 || p + dataLen > end) return null;

        byte[] img = new byte[dataLen];
        System.arraycopy(d, p, img, 0, dataLen);
        return img;
    }

    private static void readMp4(byte[] d, Tag out) {
        
        int[] moov = findBox(d, 0, d.length, "moov");
        if (moov == null) return;

        int[] udta = findBox(d, moov[0], moov[1], "udta");
        int[] meta = findBox(d, moov[0], moov[1], "meta");
        int mStart, mEnd;
        if (meta != null) {
            mStart = meta[0];
            mEnd = meta[1];
        } else if (udta != null) {
            meta = findBox(d, udta[0], udta[1], "meta");
            if (meta == null) return;
            mStart = meta[0];
            mEnd = meta[1];
        } else {
            return;
        }

        int[] ilst = findBox(d, mStart + 4, mEnd, "ilst");
        if (ilst == null) return;

        int p = ilst[0];
        while (p + 8 <= ilst[1]) {
            int size = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                    | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (size < 8 || p + size > ilst[1]) break;

            String name = new String(d, p + 4, 4, Charset.forName("ISO-8859-1"));
            boolean isTitle = name.equals("\u00A9nam");
            boolean isArtist = name.equals("\u00A9ART");
            boolean isAlbum = name.equals("\u00A9alb");
            
            boolean isCovr = name.equals("covr");

            if (isCovr) {
                int[] cdata = findBox(d, p + 8, p + size, "data");
                if (cdata != null && out.cover == null) {
                    
                    int bOff = cdata[0] + 8;
                    if (bOff < cdata[1]) {
                        int n2 = cdata[1] - bOff;
                        if (n2 > 0 && n2 < 4 * 1024 * 1024) {
                            byte[] img = new byte[n2];
                            System.arraycopy(d, bOff, img, 0, n2);
                            out.cover = img;
                        }
                    }
                }
            }

            if (isTitle || isArtist || isAlbum) {
                int[] data = findBox(d, p + 8, p + size, "data");
                if (data != null) {
                    
                    int tOff = data[0] + 8;
                    int bOff = data[0] + 16;
                    if (tOff + 4 <= data[1] && bOff < data[1]) {
                        int dtype = ((d[tOff] & 0xFF) << 16) | ((d[tOff + 1] & 0xFF) << 8) | (d[tOff + 2] & 0xFF);
                        
                        if (dtype == 1) {
                            String v = clean(new String(d, bOff, data[1] - bOff, Charset.forName("UTF-8")));
                            if (isTitle && out.title.length() == 0) out.title = v;
                            if (isArtist && out.artist.length() == 0) out.artist = v;
                            if (isAlbum && out.album.length() == 0) out.album = v;
                        }
                    }
                }
            }
            p += size;
        }
    }

    private static int[] findBox(byte[] d, int from, int to, String type) {
        if (from < 0) from = 0;
        int p = from;
        int guard = 0;
        while (p + 8 <= to && guard++ < 4096) {
            int size = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                    | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            String name = new String(d, p + 4, 4, Charset.forName("ISO-8859-1"));
            if (size == 0) size = to - p;               
            if (size < 8 || p + size > to) break;
            if (type.equals(name)) {
                return new int[]{p + 8, p + size};
            }
            p += size;
        }
        return null;
    }

    private static int le32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static String clean(String s) {
        if (s == null) return "";
        int n = s.length();
        
        while (n > 0 && s.charAt(n - 1) == '\0') n--;
        String v = s.substring(0, n);
        
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\0' || c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        
        int a = 0, b = sb.length();
        while (a < b && sb.charAt(a) == ' ') a++;
        while (b > a && sb.charAt(b - 1) == ' ') b--;
        String r = sb.substring(a, b);
        
        return r.trim();
    }
}
