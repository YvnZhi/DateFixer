# DateFixer · 影像日期修复

一款免 root 的 Android 图片/视频文件日期修复工具——把被迁移、复制、同步刷乱的文件「修改时间」拨回真正的拍摄/创建时间。整个库都是ai写的，实际上我搞这个是因为每次备份真的会乱日期，而我之前用的那个不能修复名字是时间戳的，所以有了这个。我用着是没什么大问题。

> English summary: A rootless Android app that restores the real "modified time" of photos/videos whose timestamps were ruined by migration, copying or syncing. UI and docs are in Chinese. Licensed under MIT.

## 功能

- **一键扫描诊断**：遍历 DCIM / Pictures 等媒体目录，逐文件判定「当前日期是否可信」
- **智能判定线索**（优先级从高到低）：
  1. EXIF 拍摄时间（DateTimeOriginal / CreateDate）
  2. 文件名中的日期串（如 `IMG_20220811_145938`）
  3. 文件名中的时间戳（如 `1642258674107`，含 hex 粘连兜底）
  4. 媒体库 date_taken
  - **从不采用** date_added / date_modified 作为修复目标——它们只是入库/搬运时间，不可信
- **两区分组**：
  - 有明确时间：自动勾选，一键修复为判定结果
  - 无明确时间：勾选时三选——`保持不变` / `修改为当前时间` / `修改为 2000-01-01`
- **安全可回滚**：每次修复写入 JSONL 回滚日志，`?` 菜单底部「回滚上次修复」完整还原
- 修复同时写回 EXIF 并触发媒体库刷新，相册排序即时恢复

## 判定规则（与 App 内 `?` 说明一致）

**可疑判定**——满足任一条件即列入：

- 文件当前时间早于 2001-01-01（鬼日期）
- 文件当前时间与判定结果相差 1 天以上
- 同目录下存在 3 个及以上同一秒的文件（批量迁移特征，仅对无线索文件生效）

**保守原则**：纯哈希文件名不作解析；无把握的粘连数字不作时间戳。

## 使用

1. 打开 App → 点「1. 授权文件访问」授予存储权限
2. 点「2. 扫描诊断」
3. 勾选要修复的文件 → 点「修复选中」
4. `?` 菜单有完整说明与「回滚上次修复」入口

## 构建

### 方式一：`scripts/build.sh`（实测主链路，无需 Android Studio）

纯 Java、零第三方依赖。需要：

- JDK 8+
- `android.jar`（API 30）
- `r8.jar`（D8 编译器）
- `aapt2` / `zipalign` / `apksigner`（build-tools）

```bash
KEYSTORE=my.jks STOREPASS=mypass ./scripts/build.sh
```

### 方式二：Android Studio / Gradle（标准结构，未实测）

仓库按标准 Android 工程组织，可用 Android Studio 直接打开构建。
**如实说明**：Gradle 配置是社区标准模板、未经作者实测验证（实测通过的是 `build.sh`）；欢迎 PR 帮忙校准。

## 声明

- **开发方式**：本项目是「许愿式编程」的产物，过程中没有让ai参考现有项目。
- **模型**： **mimo-v2.6-pro**。
- **判定免责**：日期判定为启发式推断，少数文件可能出现误判（检测出不正确的日期）或无法判定。检测结果仅供参考，修复前请自行确认目标日期。
- **许可**：MIT License，软件按现状提供（见 `LICENSE`）。

## 作者

**雨凩云止 · MiMo**

---

### 工具链下载地址

| 工具 | 地址 |
|---|---|
| android.jar (API 30) | `https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar` |
| r8.jar 8.3.37 | `https://maven.aliyun.com/repository/google/com/android/tools/r8/8.3.37/r8-8.3.37.jar` |
