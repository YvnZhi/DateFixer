package com.mimo.datefix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 影像日期修复 —— 主界面。
 * 布局：标题 + ? 说明 → 状态 → [1 授权][2 扫描] → 3 修复 → 统计/进度 → 列表 → [全选][全不选]
 * 列表分两区：有明确时间（可直接修复）/ 无明确时间（勾选时选择处理方式）。
 */
public class MainActivity extends Activity {

    private static final String[] ROOTS = {"DCIM", "Pictures", "Movies", "Download"};
    private static final String[] IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif"};
    private static final String[] VID_EXT = {".mp4", ".3gp", ".mkv", ".mov", ".webm", ".avi"};
    /** 2000-01-01 08:00:00（UTC+8）——无线索文件的归档时间。 */
    private static final long TS_2000 = 946684800000L;

    private Button btnPerm, btnScan, btnFix, btnHelp, btnAll, btnNone;
    private TextView status, stats, progressLine;
    private ListView lv;
    private ItemAdapter adapter;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy = false;
    private volatile int scanCount = 0;
    private volatile int doneCount = 0;
    private volatile int scanFound = 0;
    private final List<Item> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expandedDirs = new HashSet<>();

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (busy) {
                progressLine.setText("共检测到 " + scanCount + " 个文件，已判断 " + doneCount + " 个，可疑 " + scanFound + " 个");
                main.postDelayed(this, 400L);
            }
        }
    };

    /** 一个待处理文件。 */
    static class Item {
        File file;
        long curTs;         // 当前文件时间
        long newTs;         // 判断出的时间（<= 0 = 无线索）
        String source = ""; // 时间来源
        int clueLevel = 0;  // 2 = 有明确时间；0 = 无
        boolean checked = false;
        int noClueMode = 0; // 无线索时的处理：0 保持 / 1 当前时间 / 2 → 2000-01-01
    }

    /** 列表一行。 */
    static class Row {
        static final int HEADER = 0, ITEM = 1, SECTION = 2;
        int type;
        String title, dir;
        List<Item> group;
        Item item;
    }

    // ================= 界面搭建 =================

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // 标题行：标题 + 副标题 + ?
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(0);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(1);
        TextView title = new TextView(this);
        title.setText("影像日期修复");
        title.setTextSize(20.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title, wrap());
        TextView subtitle = new TextView(this);
        subtitle.setText("MiMo下令修复时间戳");
        subtitle.setTextSize(12.0f);
        subtitle.setTextColor(-7829368);
        LinearLayout.LayoutParams lpSub = wrap();
        lpSub.bottomMargin = dp(6);
        titleCol.addView(subtitle, lpSub);
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));
        btnHelp = new Button(this);
        btnHelp.setText(" ?");
        btnHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        });
        titleRow.addView(btnHelp);
        root.addView(titleRow, wrap());

        status = new TextView(this);
        status.setTextSize(14.0f);
        root.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        btnPerm = new Button(this);
        btnPerm.setText("1. 授权文件访问");
        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPermission();
            }
        });
        btnScan = new Button(this);
        btnScan.setText("2. 扫描诊断");
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        row1.addView(btnPerm, half());
        row1.addView(btnScan, half());
        root.addView(row1, wrap());

        btnFix = new Button(this);
        btnFix.setText("3. 修复选中");
        btnFix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFix();
            }
        });
        root.addView(btnFix, wrap());

        stats = new TextView(this);
        stats.setTextSize(13.0f);
        root.addView(stats, wrap());
        progressLine = new TextView(this);
        progressLine.setTextSize(12.0f);
        root.addView(progressLine, wrap());

        lv = new ListView(this);
        adapter = new ItemAdapter();
        lv.setAdapter((ListAdapter) adapter);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout row2 = new LinearLayout(this);
        btnAll = new Button(this);
        btnAll.setText("全选");
        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAll(true);
            }
        });
        btnNone = new Button(this);
        btnNone.setText("全不选");
        btnNone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAll(false);
            }
        });
        row2.addView(btnAll, half());
        row2.addView(btnNone, half());
        root.addView(row2, wrap());

        setContentView(root);
        refreshPermissionState();
    }

    // ================= 权限 =================

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
                }
                return;
            }
            toast("已有文件访问权限");
            return;
        }
        requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"}, 1001);
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void refreshPermissionState() {
        status.setText(hasPermission() ? "状态：已授权，可以扫描了" : "状态：还没授权文件访问（点按钮1）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
    }

    // ================= 扫描诊断 =================

    private void startScan() {
        if (busy) {
            toast("正在忙，等一下");
            return;
        }
        if (!hasPermission()) {
            toast("先点按钮1授权文件访问");
            return;
        }
        busy = true;
        btnScan.setEnabled(false);
        scanCount = 0;
        doneCount = 0;
        scanFound = 0;
        progressLine.setText("扫描中…");
        main.post(progressTick);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // pass 1：收集媒体文件 + 同目录同秒挤堆统计
                    List<File> files = new ArrayList<>();
                    for (String root : ROOTS) {
                        collect(new File(Environment.getExternalStorageDirectory(), root), files);
                    }
                    Map<String, Integer> secCount = new HashMap<>();
                    for (File f : files) {
                        String key = f.getParent() + "|" + f.lastModified() / 1000;
                        Integer c = secCount.get(key);
                        secCount.put(key, c == null ? 1 : c + 1);
                        scanCount++;
                    }
                    // pass 2：逐个诊断，捞出可疑
                    Map<String, Long> taken = loadDateTaken();
                    final List<Item> found = new ArrayList<>();
                    for (File f : files) {
                        long cur = f.lastModified();
                        Long t = taken.get(f.getAbsolutePath());
                        DateDoctor.Result r = DateDoctor.diagnose(f, t == null ? 0 : t);
                        doneCount++;
                        if (r.best > 0 && cur == r.best) continue;
                        Integer c = secCount.get(f.getParent() + "|" + cur / 1000);
                        boolean crowded = c != null && c >= 3 && r.best <= 0;
                        boolean junk = DateDoctor.inJunkZone(cur);
                        boolean drift = r.best > 0 && Math.abs(cur - r.best) > DateDoctor.DAY;
                        if (junk || drift || crowded) {
                            Item it = new Item();
                            it.file = f;
                            it.curTs = cur;
                            it.newTs = r.best;
                            it.source = r.source;
                            it.clueLevel = r.best > 0 ? 2 : 0;
                            it.checked = it.clueLevel == 2;
                            found.add(it);
                            scanFound++;
                        }
                    }
                    scanFound = found.size();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            items.clear();
                            items.addAll(found);
                            rebuildRows();
                            adapter.notifyDataSetChanged();
                            updateStats();
                            busy = false;
                            btnScan.setEnabled(true);
                            progressLine.setText("扫描完成：共检测到 " + scanCount + " 个文件，已判断 " + doneCount + " 个，可疑 " + found.size() + " 个");
                        }
                    });
                } catch (Throwable t) {
                    final String err = String.valueOf(t);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            btnScan.setEnabled(true);
                            progressLine.setText("扫描出错：" + err);
                        }
                    });
                }
            }
        }).start();
    }

    /** 递归收集媒体文件（跳过 . 开头的隐藏文件夹）。 */
    private void collect(File dir, List<File> out) {
        try {
            File[] arr = dir.listFiles();
            if (arr == null) {
                return;
            }
            for (File f : arr) {
                if (f.isDirectory()) {
                    if (!f.getName().startsWith(".")) {
                        collect(f, out);
                    }
                } else if (isMedia(f.getName())) {
                    out.add(f);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isMedia(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : IMG_EXT) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        for (String e : VID_EXT) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /** 读媒体库 date_taken（拍摄时刻）。date_added / date_modified 永不作目标。 */
    private Map<String, Long> loadDateTaken() {
        Map<String, Long> map = new HashMap<>();
        ContentResolver cr = getContentResolver();
        try {
            Cursor c = cr.query(MediaStore.Files.getContentUri("external"),
                    new String[]{"_data", "date_taken"}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String path = c.getString(0);
                    if (path != null) {
                        map.put(path, c.getLong(1));
                    }
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    // ================= 修复选中 =================

    /** 目标时间：明确时间 → 判断结果；无线索按所选处理方式。0 = 不可修。 */
    private long effTs(Item item) {
        if (item.clueLevel == 2) {
            return item.newTs;
        }
        if (item.noClueMode == 1) {
            return System.currentTimeMillis();
        }
        return item.noClueMode == 2 ? TS_2000 : 0L;
    }

    private void startFix() {
        if (busy) {
            toast("正在忙，等一下");
            return;
        }
        final List<Item> targets = new ArrayList<>();
        for (Item item : items) {
            if (item.checked && effTs(item) > 0) {
                targets.add(item);
            }
        }
        if (targets.isEmpty()) {
            toast("没选中可修的文件");
            return;
        }
        busy = true;
        btnFix.setEnabled(false);
        progressLine.setText("修复中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File logFile = Fixer.newLogFile(Environment.getExternalStorageDirectory());
                final List<Item> done = new ArrayList<>();
                List<String> paths = new ArrayList<>();
                int ok = 0;
                int fail = 0;
                for (int i = 0; i < targets.size(); i++) {
                    Item item = targets.get(i);
                    if (Fixer.fixOne(item.file, effTs(item), true, item.source, logFile)) {
                        ok++;
                        done.add(item);
                        paths.add(item.file.getAbsolutePath());
                    } else {
                        fail++;
                    }
                    if (i % 20 == 0 || i == targets.size() - 1) {
                        final int n = i + 1;
                        final int total = targets.size();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                progressLine.setText("修复中… " + n + "/" + total);
                            }
                        });
                    }
                }
                if (!paths.isEmpty()) {
                    MediaScannerConnection.scanFile(getApplicationContext(),
                            paths.toArray(new String[0]), null, null);
                }
                final int okCount = ok;
                final int failCount = fail;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        items.removeAll(done);
                        rebuildRows();
                        adapter.notifyDataSetChanged();
                        updateStats();
                        busy = false;
                        btnFix.setEnabled(true);
                        progressLine.setText("修复完成：成功 " + okCount + "，失败 " + failCount
                                + "\n日志：" + logFile.getAbsolutePath()
                                + "\n相册显示可能要稍等媒体库刷新。");
                        toast("修复完成：成功 " + okCount + " / 失败 " + failCount);
                    }
                });
            }
        }).start();
    }

    // ================= 回滚（? 菜单底部） =================

    private void startRollback() {
        if (busy) {
            toast("正在忙，等一下");
            return;
        }
        List<File> logs = Fixer.listLogs(Environment.getExternalStorageDirectory());
        if (logs.isEmpty()) {
            toast("没有可回滚的修复日志");
            return;
        }
        final File logFile = logs.get(0);
        busy = true;
        progressLine.setText("回滚中… " + logFile.getName());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int[] r = Fixer.rollback(logFile);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        progressLine.setText("回滚完成（" + logFile.getName() + "）：还原 " + r[0]
                                + "，失败 " + r[1] + "\n建议再点一次「扫描诊断」确认。");
                        toast("回滚完成：还原 " + r[0] + " 个");
                    }
                });
            }
        }).start();
    }

    // ================= 使用说明（?） =================

    private void showHelp() {
        String msg =
                "【扫描范围】\n" +
                "· 文件夹：DCIM、Pictures、Movies、Download（含全部子目录）\n" +
                "· 跳过以 . 开头的隐藏文件夹（如 .thumbnails）\n" +
                "· 图片格式：jpg / jpeg / png / webp / gif / bmp / heic / heif\n" +
                "· 视频格式：mp4 / 3gp / mkv / mov / webm / avi\n\n" +
                "【时间判断依据】按优先级从高到低取第一个有效项：\n" +
                "1. EXIF 拍摄时间（DateTimeOriginal / DateTimeDigitized / DateTime）\n" +
                "2. 文件名中的日期串（例：IMG_20250721_220145）\n" +
                "3. 文件名中的时间戳（13 位毫秒或 10 位秒，例：wx_camera_1651573657321）\n" +
                "   · 纯哈希文件名（32/40/64 位十六进制）不含时间信息，不作解析\n" +
                "4. 媒体库 date_taken 字段\n" +
                "注：媒体库 date_added / date_modified 为文件写入本机的时间，\n" +
                "  不代表拍摄时间，不作为修复目标。\n\n" +
                "【可疑判定】满足任一条件：\n" +
                "· 文件当前时间早于 2001-01-01\n" +
                "· 文件当前时间与判断结果相差 1 天以上\n" +
                "· 同目录下存在 3 个及以上同一秒的文件（批量迁移特征）\n\n" +
                "【处理方式】\n" +
                "· 有明确时间：勾选后修复为判断结果\n" +
                "· 无明确时间：勾选时可选择\n" +
                "  保持不变 / 修改为当前时间 / 修改为 2000-01-01\n\n" +
                "【安全保证】\n" +
                "· 每次修复写入回滚日志，可由「回滚上次修复」完整还原\n" +
                "· 仅修改文件时间戳，不修改文件内容\n\n" +
                "【声明】\n" +
                "· 日期判定为启发式推断，少数文件可能出现误判\n" +
                "  （检测出不正确的日期）或无法判定\n" +
                "· 检测结果仅供参考，修复前请自行确认目标日期\n\n" +
                "____________________\n" +
                "版本 0.726（2026-11-05）\n" +
"作者：雨凩云止 · MiMo";
        new AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setMessage(msg)
                .setNeutralButton("回滚上次修复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startRollback();
                    }
                })
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 无线索文件的处理方式三选。 */
    private void showNoClueDialog(final Item item) {
        final String[] options = {"保持原始状态（不修复）", "改成当前时间", "改成 2000-01-01"};
        new AlertDialog.Builder(this)
                .setTitle(item.file.getName())
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            item.checked = false;
                            item.noClueMode = 0;
                        } else {
                            item.checked = true;
                            item.noClueMode = which;
                        }
                        adapter.notifyDataSetChanged();
                        updateStats();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 列表与统计 =================

    private void setAll(boolean z) {
        for (Item item : items) {
            if (item.clueLevel == 2) {
                item.checked = z;
            } else if (!z) {
                item.checked = false;
                item.noClueMode = 0;
            }
        }
        adapter.notifyDataSetChanged();
        updateStats();
    }

    private void rebuildRows() {
        rows.clear();
        String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        TreeMap<String, List<Item>> withClue = new TreeMap<>();
        TreeMap<String, List<Item>> noClue = new TreeMap<>();
        for (Item item : items) {
            TreeMap<String, List<Item>> map = item.clueLevel == 2 ? withClue : noClue;
            String parent = item.file.getParent() == null ? "" : item.file.getParent();
            List<Item> list = map.get(parent);
            if (list == null) {
                list = new ArrayList<>();
                map.put(parent, list);
            }
            list.add(item);
        }
        appendSection("▼ 有明确时间（可直接修复）", withClue, basePath);
        appendSection("▼ 无明确时间（勾选时选择处理方式）", noClue, basePath);
    }

    private void appendSection(String title, Map<String, List<Item>> map, String basePath) {
        if (map.isEmpty()) {
            return;
        }
        int total = 0;
        for (List<Item> list : map.values()) {
            total += list.size();
        }
        Row section = new Row();
        section.type = Row.SECTION;
        section.title = title + "    共 " + total + " 个";
        rows.add(section);
        for (Map.Entry<String, List<Item>> entry : map.entrySet()) {
            Row header = new Row();
            header.type = Row.HEADER;
            String dir = entry.getKey();
            header.title = dir.startsWith(basePath) ? dir.substring(basePath.length()) : dir;
            header.dir = dir;
            header.group = entry.getValue();
            rows.add(header);
            if (expandedDirs.contains(dir)) {
                for (Item item : entry.getValue()) {
                    Row row = new Row();
                    row.type = Row.ITEM;
                    row.item = item;
                    rows.add(row);
                }
            }
        }
    }

    private void updateStats() {
        int fixable = 0;
        int checked = 0;
        for (Item item : items) {
            if (effTs(item) > 0) {
                fixable++;
            }
            if (item.checked) {
                checked++;
            }
        }
        int dirs = 0;
        for (Row row : rows) {
            if (row.type == Row.HEADER) {
                dirs++;
            }
        }
        stats.setText("共 " + items.size() + " 个可疑（" + dirs + " 个文件夹），可修 "
                + fixable + " 个，已勾选 " + checked + " 个");
    }

    // ================= 列表适配器 =================

    class ItemAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Row row = rows.get(position);

            // 分区标题
            if (row.type == Row.SECTION) {
                TextView tv = new TextView(MainActivity.this);
                tv.setText(row.title);
                tv.setTextSize(14.0f);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setPadding(dp(6), dp(12), dp(6), dp(6));
                tv.setBackgroundColor(2780830);
                return tv;
            }

            // 目录组头（折叠 + 组勾选）
            if (row.type == Row.HEADER) {
                LinearLayout lay = new LinearLayout(MainActivity.this);
                lay.setOrientation(0);
                lay.setPadding(dp(4), dp(8), dp(4), dp(8));
                lay.setBackgroundColor(428378248);
                lay.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!expandedDirs.remove(row.dir)) {
                            expandedDirs.add(row.dir);
                        }
                        rebuildRows();
                        notifyDataSetChanged();
                    }
                });
                int fixable = 0;
                int checked = 0;
                for (Item item : row.group) {
                    if (item.clueLevel == 2) {
                        fixable++;
                        if (item.checked) {
                            checked++;
                        }
                    }
                }
                CheckBox cb = new CheckBox(MainActivity.this);
                cb.setFocusable(false);
                cb.setChecked(fixable > 0 && checked == fixable);
                cb.setTag(row.group);
                cb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean on = ((CheckBox) v).isChecked();
                        for (Item item : (List<Item>) v.getTag()) {
                            if (item.clueLevel == 2) {
                                item.checked = on;
                            }
                        }
                        notifyDataSetChanged();
                        updateStats();
                    }
                });
                lay.addView(cb);
                TextView tv = new TextView(MainActivity.this);
                tv.setTextSize(12.0f);
                tv.setMaxLines(1);
                boolean open = expandedDirs.contains(row.dir);
                tv.setText((open ? "▼ " : "▶ ") + row.title
                        + "   （" + row.group.size() + " 个，可修 " + fixable + "，已选 " + checked + "）");
                lay.addView(tv, new LinearLayout.LayoutParams(0, -2, 1.0f));
                return lay;
            }

            // 文件行
            final Item item = row.item;
            LinearLayout lay = new LinearLayout(MainActivity.this);
            lay.setOrientation(0);
            lay.setPadding(dp(4), dp(6), dp(4), dp(6));
            lay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (item.newTs <= 0) {
                        showNoClueDialog(item);
                    } else {
                        item.checked = !item.checked;
                        notifyDataSetChanged();
                        updateStats();
                    }
                }
            });
            CheckBox cb = new CheckBox(MainActivity.this);
            cb.setFocusable(false);
            cb.setChecked(item.checked);
            cb.setTag(item);
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Item it = (Item) v.getTag();
                    if (it.newTs <= 0) {
                        showNoClueDialog(it);
                    } else {
                        it.checked = ((CheckBox) v).isChecked();
                        updateStats();
                    }
                }
            });
            lay.addView(cb);
            LinearLayout textCol = new LinearLayout(MainActivity.this);
            textCol.setOrientation(1);
            textCol.setPadding(dp(4), 0, 0, 0);
            TextView name = new TextView(MainActivity.this);
            name.setText(item.file.getName());
            name.setTextSize(13.0f);
            name.setMaxLines(1);
            textCol.addView(name);
            TextView detail = new TextView(MainActivity.this);
            detail.setTextSize(11.0f);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            String cur = item.curTs > 0 ? fmt.format(new Date(item.curTs)) : "?";
            String target;
            if (item.clueLevel == 2) {
                target = fmt.format(new Date(item.newTs));
            } else if (item.noClueMode == 1) {
                target = "当前时间";
            } else if (item.noClueMode == 2) {
                target = "2000-01-01 08:00";
            } else {
                target = "无日期线索（点击选择处理）";
            }
            detail.setText(cur + "  →  " + target + "   [" + item.source + "]");
            textCol.addView(detail);
            lay.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));
            return lay;
        }
    }

    // ================= 小工具 =================

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams half() {
        return new LinearLayout.LayoutParams(0, -2, 1.0f);
    }
}