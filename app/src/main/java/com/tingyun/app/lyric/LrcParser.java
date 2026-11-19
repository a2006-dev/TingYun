package com.tingyun.app.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcParser {

    public static final int PAIR_TOLERANCE_MS = 150;

    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private static final String[] META_PREFIX = {
            "[by:", "[ti:", "[ar:", "[al:", "[offset:", "[re:", "[ve:", "[au:",
            "[length:", "[total:", "[sign:", "[qq:", "[kana:", "[tool:"
    };

    private static final String[] META_KEYS = {
            "作词", "作曲", "编曲", "制作人", "录音师", "录音", "混音", "母带",
            "和声", "监制", "出品", "发行", "统筹", "策划", "吉他", "贝斯", "鼓",
            "弦乐", "键盘", "配唱", "人声", "合唱", "编写", "OP", "SP",
            "Lyrics", "Composer", "Arranger", "Producer", "Mixed", "Mastered"
    };

    private LrcParser() {
    }

    public static final class Line {
        public final long timeMs;
        public final String text;
        
        public String trans = "";

        Line(long t, String s) {
            this.timeMs = t;
            this.text = s;
        }

        public boolean hasTrans() {
            return trans != null && trans.length() > 0;
        }
    }

    public static List<Line> parse(String raw) {
        List<Line> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) return out;

        String[] rows = raw.split("\r\n|\r|\n");
        for (String row : rows) {
            if (row == null) continue;
            String s = row.trim();
            if (s.length() == 0) continue;

            if (isMetaLine(s)) continue;

            List<Long> stamps = new ArrayList<>(2);
            Matcher m = TIME_TAG.matcher(s);
            int end = 0;
            while (m.find()) {
                long mm = parseLong(m.group(1));
                long ss = parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null && frac.length() > 0) {
                    
                    ms = frac.length() == 1 ? parseLong(frac) * 100
                            : frac.length() == 2 ? parseLong(frac) * 10
                            : parseLong(frac.substring(0, 3));
                }
                stamps.add((mm * 60 + ss) * 1000 + ms);
                end = m.end();
            }
            
            if (stamps.isEmpty()) continue;

            String text = s.substring(end).trim();
            if (text.length() == 0) continue;
            if (isMetaText(text)) continue;

            for (Long t : stamps) out.add(new Line(t, text));
        }

        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return out;
    }

    public static void pairTranslation(List<Line> main, List<Line> trans) {
        if (main == null || main.isEmpty() || trans == null || trans.isEmpty()) return;

        for (Line t : trans) {
            int best = -1;
            int bestDiff = Integer.MAX_VALUE;
            for (int i = 0; i < main.size(); i++) {
                int diff = (int) Math.abs(main.get(i).timeMs - t.timeMs);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = i;
                }
            }
            if (best >= 0 && bestDiff <= PAIR_TOLERANCE_MS) {
                Line host = main.get(best);
                
                if (!host.hasTrans() && !t.text.equals(host.text)) host.trans = t.text;
            }
        }
    }

    public static Line currentAt(List<Line> lines, long posMs) {
        if (lines == null || lines.isEmpty()) return null;
        Line cur = null;
        for (Line l : lines) {
            if (l.timeMs <= posMs) cur = l;
            else break;
        }
        return cur;
    }

    private static boolean isMetaLine(String s) {
        String low = s.toLowerCase();
        for (String p : META_PREFIX) {
            if (low.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean isMetaText(String s) {
        int colon = indexOfColon(s);
        if (colon <= 0 || colon > 14) return false;
        String left = s.substring(0, colon).trim();
        for (String k : META_KEYS) {
            if (left.equalsIgnoreCase(k) || left.startsWith(k)) return true;
        }
        return false;
    }

    private static int indexOfColon(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' || c == '：') return i;
        }
        return -1;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }
}
