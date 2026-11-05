package com.mimo.datefix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 修复执行器：改文件时间戳（+ 图片 EXIF），每一步都记进回滚日志。
 * 日志位置：<外部存储>/DateFixer/logs/fix_yyyyMMdd_HHmmss.jsonl
 */
public class Fixer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 回滚日志条目。 */
    public static class LogEntry {
        public String path;
        public long oldMtime;
        public long newMtime;
        public String oldExif;
        public boolean exifWritten;
        public String source;
    }

    /** 日志目录（不存在则创建）。 */
    public static File logDir(File base) {
        File dir = new File(base, "DateFixer/logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 本次修复的新日志文件。 */
    public static File newLogFile(File base) {
        String name = "fix_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jsonl";
        return new File(logDir(base), name);
    }

    /**
     * 修复一个文件：改 mtime，必要时重写图片 EXIF，并记入回滚日志。
     *
     * @param writeExif 是否同时写 EXIF（让相册也能读到正确日期）
     * @return mtime 是否修改成功
     */
    public static boolean fixOne(File f, long ts, boolean writeExif, String source, File logFile) {
        long oldMtime = f.lastModified();
        String oldExif = DateDoctor.exifRaw(f);
        // 先写 EXIF（saveAttributes 会重写文件、把 mtime 刷成当下）
        boolean exifDone = writeExif && DateDoctor.writeExif(f, ts);
        // 最后统一拨 mtime，任何后续操作都不许再碰文件
        boolean ok = f.setLastModified(ts);
        if (ok && Math.abs(f.lastModified() - ts) > 2000) {
            ok = false;
        }
        if (ok) {
            LogEntry e = new LogEntry();
            e.path = f.getAbsolutePath();
            e.oldMtime = oldMtime;
            e.newMtime = ts;
            e.oldExif = oldExif;
            e.exifWritten = exifDone;
            e.source = source;
            appendLog(logFile, e);
        }
        return ok;
    }

    /**
     * 回滚一个日志：把里面每个文件还原回 oldMtime / oldExif。
     *
     * @return {还原成功数, 失败数}
     */
    public static int[] rollback(File logFile) {
        int ok = 0;
        int fail = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(logFile));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    String path = jsonStr(line, "path");
                    long oldMtime = jsonLong(line, "oldMtime", -1);
                    String oldExif = jsonStr(line, "oldExif");
                    if (path == null || oldMtime <= 0) {
                        fail++;
                        continue;
                    }
                    File f = new File(path);
                    if (!f.exists()) {
                        fail++;
                        continue;
                    }
                    boolean done = f.setLastModified(oldMtime);
                    if (oldExif != null) {
                        DateDoctor.restoreExif(f, oldExif);
                    }
                    if (done) {
                        ok++;
                    } else {
                        fail++;
                    }
                } catch (Throwable t) {
                    fail++;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return new int[]{ok, fail};
    }

    /** 全部回滚日志，按文件名倒序（最新的在前）。 */
    public static List<File> listLogs(File base) {
        List<File> out = new ArrayList<>();
        File[] arr = logDir(base).listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.getName().endsWith(".jsonl")) {
                    out.add(f);
                }
            }
            Collections.sort(out, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return b.getName().compareTo(a.getName());
                }
            });
        }
        return out;
    }

    // ---------------- 内部：JSONL 持久化 ----------------

    private static void appendLog(File logFile, LogEntry e) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"path\":").append(quote(e.path));
            sb.append(",\"oldMtime\":").append(e.oldMtime);
            sb.append(",\"newMtime\":").append(e.newMtime);
            sb.append(",\"oldExif\":").append(e.oldExif == null ? "null" : quote(e.oldExif));
            sb.append(",\"exifWritten\":").append(e.exifWritten);
            sb.append(",\"source\":").append(quote(e.source));
            sb.append("}\n");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(logFile, true), UTF8);
            w.write(sb.toString());
            w.flush();
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < ' ') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 从 JSONL 行里取字符串字段，取不到返回 null。 */
    private static String jsonStr(String line, String key) {
        String pat = "\"" + key + "\":";
        int i = line.indexOf(pat);
        if (i < 0) {
            return null;
        }
        int p = i + pat.length();
        if (p >= line.length() || line.charAt(p) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        p++;
        while (p < line.length()) {
            char c = line.charAt(p);
            if (c == '\\') {
                p++;
                if (p < line.length()) {
                    sb.append(line.charAt(p));
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
            p++;
        }
        return null;
    }

    /** 从 JSONL 行里取整数字段，取不到返回 def。 */
    private static long jsonLong(String line, String key, long def) {
        String pat = "\"" + key + "\":";
        int i = line.indexOf(pat);
        if (i < 0) {
            return def;
        }
        int p = i + pat.length();
        int start = p;
        while (p < line.length() && (Character.isDigit(line.charAt(p)) || line.charAt(p) == '-')) {
            p++;
        }
        try {
            return Long.parseLong(line.substring(start, p));
        } catch (Exception e) {
            return def;
        }
    }
}