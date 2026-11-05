package com.mimo.datefix;

import android.media.ExifInterface;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期诊断器：从 EXIF / 文件名 / 媒体库里找出文件的真实日期。
 * 判定优先级（与「使用说明」一致）：
 *   1. EXIF 拍摄时间
 *   2. 文件名中的日期串
 *   3. 文件名中的时间戳
 *   4. 媒体库 date_taken
 * 媒体库 date_added / date_modified 是写入本机的时间，永不作为修复目标。
 */
public class DateDoctor {

    /** 2001-01-01 08:00:00（UTC+8）。早于它的都是垃圾时间。 */
    public static final long TS_LO = 978307200000L;
    /** 一天的毫秒数。 */
    public static final long DAY = 86400000L;
    /** 未来容差：今 + 2 天。 */
    public static final long FUTURE = 172800000L;

    public static final String SRC_EXIF = "EXIF";
    public static final String SRC_NAME_DATE = "文件名日期";
    public static final String SRC_NAME_TS = "文件名时间戳";
    public static final String SRC_MS_TAKEN = "媒体库date_taken";

    // 文件名日期串：20250721_220145 / 2025-07-21 / 2025年07月 等
    private static final Pattern P_FULL = Pattern.compile(
            "(20\\d{2})[-_.年]?(\\d{2})[-_.月]?(\\d{2})[-_.日 _T]?(\\d{2})[-_.:]?(\\d{2})[-_.:]?(\\d{2})");
    private static final Pattern P_YMD = Pattern.compile(
            "(20\\d{2})[-_.年]?(\\d{2})[-_.月]?(\\d{2})(?!\\d)");
    private static final Pattern P_YM = Pattern.compile(
            "(20\\d{2})[-_.年](\\d{2})(?!\\d)");
    // 文件名时间戳：独立的 13 位毫秒 / 10 位秒（1[3-7] 开头 = 2011~2026 段）
    private static final Pattern P_TS13 = Pattern.compile("(?<!\\d)(1[3-7]\\d{11})(?!\\d)");
    private static final Pattern P_TS10 = Pattern.compile("(?<!\\d)(1[3-7]\\d{8})(?!\\d)");
    // 粘连长数字（QQ 视频命名 ...ae47|1641742712 之类）
    private static final Pattern P_RUN = Pattern.compile("(?<!\\d)(\\d{11,})(?!\\d)");

    /** 诊断结果。 */
    public static class Result {
        /** 找到的最佳时间（毫秒），-1 = 无线索。 */
        public long best = -1;
        /** 来源说明。 */
        public String source = "";
        /** EXIF 里有日期文本但解析坏了（修复时顺手重写）。 */
        public boolean exifBroken = false;
    }

    /** 是否垃圾时间（<= 0 或早于 2001）。 */
    public static boolean inJunkZone(long t) {
        return t <= 0 || t < TS_LO;
    }

    /** 时间是否可信（2001 ~ 今 + 2 天）。 */
    public static boolean trustworthy(long t) {
        return t > 0 && t >= TS_LO && t <= System.currentTimeMillis() + FUTURE;
    }

    // ---------------- EXIF ----------------

    /** 读 EXIF 拍摄时间（三个日期标签取第一个可信的）。 */
    public static long exifTime(File f) {
        if (!isImage(f)) {
            return -1;
        }
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            String[] tags = {"DateTimeOriginal", "DateTimeDigitized", "DateTime"};
            for (String tag : tags) {
                long t = parseExifDate(ex.getAttribute(tag));
                if (t > 0) {
                    return t;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 读 EXIF DateTime 原文（回滚时原样写回）。 */
    public static String exifRaw(File f) {
        try {
            return new ExifInterface(f.getAbsolutePath()).getAttribute("DateTime");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把 EXIF 三个日期标签写成指定时间（仅图片格式）。 */
    public static boolean writeExif(File f, long ts) {
        if (!isImage(f)) {
            return false;
        }
        try {
            String v = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(ts));
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            ex.setAttribute("DateTimeOriginal", v);
            ex.setAttribute("DateTimeDigitized", v);
            ex.setAttribute("DateTime", v);
            ex.saveAttributes();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 回滚时把 EXIF DateTime 原文写回。 */
    public static boolean restoreExif(File f, String raw) {
        if (raw == null) {
            return false;
        }
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            ex.setAttribute("DateTime", raw);
            ex.saveAttributes();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 解析 EXIF 日期文本（yyyy:MM:dd HH:mm:ss），坏的返回 -1。 */
    public static long parseExifDate(String s) {
        if (s == null) {
            return -1;
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            fmt.setLenient(false);
            Date d = fmt.parse(s.trim());
            if (d == null) {
                return -1;
            }
            long t = d.getTime();
            return trustworthy(t) ? t : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 文件名 ----------------

    /**
     * 从文件名提取日期。优先级（与「使用说明」一致）：
     * 日期串 → 时间戳 → hex 粘连兜底。
     * 纯哈希名（32/40/64 位全十六进制）不含时间信息，不作解析。
     */
    public static Result nameTime(String fileName) {
        Result r = new Result();
        long hi = System.currentTimeMillis() + FUTURE;

        // 纯哈希闸门：内容指纹不是生日
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (isPureHash(base)) {
            return r;
        }

        Matcher m;
        // 1) 文件名日期串：年月日时分秒 / 年月日 / 年月
        m = P_FULL.matcher(fileName);
        if (m.find()) {
            long t = compose(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6));
            if (t > 0) {
                r.best = t;
                r.source = SRC_NAME_DATE;
                return r;
            }
        }
        m = P_YMD.matcher(fileName);
        if (m.find()) {
            long t = compose(m.group(1), m.group(2), m.group(3), "00", "00", "00");
            if (t > 0) {
                r.best = t;
                r.source = SRC_NAME_DATE;
                return r;
            }
        }
        m = P_YM.matcher(fileName);
        if (m.find()) {
            long t = compose(m.group(1), m.group(2), "01", "00", "00", "00");
            if (t > 0) {
                r.best = t;
                r.source = SRC_NAME_DATE;
                return r;
            }
        }
        // 2) 文件名时间戳：独立 13 位毫秒 / 10 位秒
        m = P_TS13.matcher(fileName);
        if (m.find()) {
            long t = Long.parseLong(m.group(1));
            if (t >= TS_LO && t <= hi) {
                r.best = t;
                r.source = SRC_NAME_TS;
                return r;
            }
        }
        m = P_TS10.matcher(fileName);
        if (m.find()) {
            long t = Long.parseLong(m.group(1)) * 1000L;
            if (t >= TS_LO && t <= hi) {
                r.best = t;
                r.source = SRC_NAME_TS;
                return r;
            }
        }
        // 3) hex 粘连兜底（QQ 视频命名 ...ae47|1641742712）
        m = P_RUN.matcher(fileName);
        while (m.find()) {
            String run = m.group(1);
            int st = m.start(1);
            if (run.length() > 16 || st == 0) {
                continue;
            }
            char prev = fileName.charAt(st - 1);
            boolean glued = (prev >= 'a' && prev <= 'f') || (prev >= 'A' && prev <= 'F');
            if (!glued) {
                continue;
            }
            try {
                // 尾 13 位当毫秒（run 至少 13 位）
                if (run.length() >= 13) {
                    long t = Long.parseLong(run.substring(run.length() - 13));
                    if (t >= TS_LO && t <= hi) {
                        r.best = t;
                        r.source = SRC_NAME_TS;
                        return r;
                    }
                }
                // 尾 10 位当秒（run 至少 12 位，防 11 位随机 ID 被榨出鬼日期）
                if (run.length() >= 12) {
                    long t = Long.parseLong(run.substring(run.length() - 10)) * 1000L;
                    if (t >= TS_LO && t <= hi) {
                        r.best = t;
                        r.source = SRC_NAME_TS;
                        return r;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    /** 主体名是否为纯哈希（32/40/64 位全十六进制）。 */
    private static boolean isPureHash(String base) {
        int len = base.length();
        if (len != 32 && len != 40 && len != 64) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = base.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 拼出完整日期并做窗口校验（2001 ~ 今 + 2 天）。 */
    private static long compose(String y, String mo, String d, String h, String mi, String s) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            fmt.setLenient(false);
            Date date = fmt.parse(y + "-" + mo + "-" + d + " " + h + ":" + mi + ":" + s);
            if (date == null) {
                return -1;
            }
            long t = date.getTime();
            return trustworthy(t) ? t : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 媒体库 ----------------

    /**
     * 媒体库 date_taken（拍摄时刻）。
     * date_added / date_modified 是写入本机的时间，永不作为修复目标。
     */
    public static long mediaStoreTime(long dateTaken) {
        return trustworthy(dateTaken) ? dateTaken : -1;
    }

    // ---------------- 综合诊断 ----------------

    /**
     * 综合诊断：EXIF → 文件名 → 媒体库 date_taken，取第一个有效项。
     * exifBroken = EXIF 有日期文本但解析失败（修复时顺手重写）。
     */
    public static Result diagnose(File f, long dateTaken) {
        Result r = new Result();

        long exif = exifTime(f);
        if (exif > 0) {
            r.best = exif;
            r.source = SRC_EXIF;
            return r;
        }
        // EXIF 里有文本但坏了：标记出来
        try {
            String raw = new ExifInterface(f.getAbsolutePath()).getAttribute("DateTime");
            if (raw != null && parseExifDate(raw) <= 0) {
                r.exifBroken = true;
            }
        } catch (Throwable ignored) {
        }

        Result fromName = nameTime(f.getName());
        if (fromName.best > 0) {
            r.best = fromName.best;
            r.source = fromName.source;
            return r;
        }

        long taken = mediaStoreTime(dateTaken);
        if (taken > 0) {
            r.best = taken;
            r.source = SRC_MS_TAKEN;
        }
        return r;
    }

    /** 是否图片格式（可读写 EXIF）。 */
    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".heif");
    }
}