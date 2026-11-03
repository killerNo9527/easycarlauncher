# md3Music 收藏（红心）接口调研

> 仓库：https://github.com/zzyoxml/md3Music（公开分支 `rust-local-force`）
> 设备实测版本：`com.md3music.md3music` 6.2.0（versionCode 2053，targetSdk 35）
> 结论：**没有任何可用于「同步控制收藏」的公开接口**，红心只能在本 App 内操作。

---

## 一、三条证据

### 1. MCP 文档明确写了"没有收藏工具"

`MCP_GUIDE.md` 原文：

> **为什么没有"收藏歌曲"工具**：经上游复核，酷狗开放接口**不存在单曲收藏写端点**（只有收藏数查询），
> 故不做一个必然失败的工具。收藏/取消收藏请在 App 内操作。

能做的只有"加入歌单"（语义不同，且需要 `listid` + 二次确认）：

| 工具 | 参数 | 说明 |
|---|---|---|
| `md3_add_song_to_playlist` | `hash`、`listid` | 加入指定歌单（需二次确认） |
| `md3_create_playlist` | `name` | 新建歌单（需二次确认） |

### 2. 收藏数据存在 App 私有 SharedPreferences 里

`lib/data/repositories/favorites_repository.dart` 全文只有本地读写：

```dart
static const String _baseKey = 'favorite_songs';
static const int _maxCount = 500;

String get _key {                       // 登录时按账号隔离
  final uid = KugouApiClient().userid;
  return uid == null ? _baseKey : '${_baseKey}_$uid';
}

Future<void> addFavorite(Song song) async {
  final prefs = await SharedPreferences.getInstance();
  ...
  await prefs.setStringList(_key, jsonList);   // 仅本地 prefs，无网络、无 IPC
}
```

关键点：**没有网络请求、没有 MethodChannel、没有广播、没有 ContentProvider**。
数据落在 `/data/data/com.md3music.md3music/shared_prefs/*.xml`，跨应用无法读写（除非 root）。

### 3. MediaSession 服务不承载收藏命令

`MD3MusicMediaSessionService.kt` 继承 media3 的 `MediaSessionService`，但注释写得很清楚：

> 职责：把 just_audio fork 自建的 MediaSession 归入本服务，由 media3 的
> MediaNotificationManager 生成系统 now playing 通知……**仅作会话承载，不承担播放/焦点逻辑**。

也就是说：会话只负责"通知栏 + 外部绑定"，自定义命令（`SessionCommand`）需要 Dart 侧显式注册，
而这个服务里没有收藏相关的 `onCustomCommand` 分支。

> 顺带纠正我之前的判断：该服务的清单**确实**声明了
> `androidx.media3.session.MediaSessionService` 与 `android.media.browse.MediaBrowserService`，
> 所以 MediaSession 连接本身是可行的（音乐卡已能拿到真实歌名/歌词）。

---

## 二、所以红心同步这条路是断的

| 想做的事 | 可行性 |
|---|---|
| 读"当前歌曲是否已收藏" | ✗ 元数据里没有该字段，MCP 也不返回 |
| 点红心 → App 里收藏 | ✗ 无写入端点，MCP 未实现，prefs 不可跨应用写 |
| 点红心 → 加入某个歌单 | △ 可以（`md3_add_song_to_playlist`），但语义变了，还要 `listid` + 二次确认 |

---

## 三、三条可选替代方案

### A. 红心改成「打开音乐」入口（推荐，改动最小）

图标语义从"收藏"改为"进入音乐 App"，点按直接拉起 md3Music。
不撒谎、不依赖任何接口，且车机上"一键进播放器"本身就有用。

### B. 红心改成「加入歌单」（语义变化，需要设置）

走 MCP：`md3_get_player_state` 拿 `hash` → `md3_add_song_to_playlist(hash, listid)`。
需要：① 用户开启 MCP 服务；② 预先指定一个目标歌单 id；③ App 侧会弹二次确认。

### C. 去掉红心，换成真正可用的开关

MCP 里有现成的写接口：`md3_set_desktop_lyric(enabled)`、`md3_set_eq_enabled`、`md3_set_loop_mode`。
车机上"桌面歌词开关 / 循环模式"比一个假红心实用。

---

## 四、顺带发现：MCP 是比 MediaSession 更好的数据通道

`MCP_GUIDE.md` 里 44 个工具，对本工程直接有用的：

| 工具 | 用途 |
|---|---|
| `md3_get_player_state` | 曲名/歌手/专辑、播放态、`position_ms`（真实值）、时长、音量、循环、队列索引、**hash** |
| `md3_get_lyric(hash)` | 直接返回 LRC 明文（≤8000 字符，超长带 `truncated`） |
| `md3_toggle` / `md3_next` / `md3_previous` / `md3_seek` | 播放控制 |
| `md3_get_queue(limit)` | 队列 |
| `md3_wait_for_player_event` | 长轮询等切歌/播放/暂停 |

接入条件：

- 端点 `POST http://127.0.0.1:17888/mcp`（端口可能顺延到 17889–17892，以设置页显示为准）
- 协议 `2025-06-18`，回环**不需要令牌**，局域网需要 `Authorization: Bearer <43 字符 token>`
- **默认关闭**：设置 → 应用与管理 → AI 代理 → 启用 MCP 服务
- App 进程被划掉后端口失效

用它能彻底绕开 MediaSession extras 的不确定性：歌词按 hash 现取，状态按需拉取。
