# Forge 碧蓝幻想（GBF）自定义卡牌脚本项目

本项目是基于开源万智牌游戏引擎 **Card-Forge**（[github.com/Card-Forge/forge](https://github.com/Card-Forge/forge)，GPL-3.0 协议）的**自定义卡牌脚本项目**，为 Forge 游戏添加了一套完整的《碧蓝幻想》（Granblue Fantasy，代码 `GBF`）主题系列：包括卡牌脚本、衍生物脚本、系列定义与配套的测试/回归工具。

> ⚠️ **重要**：本项目的卡牌脚本使用了**魔改后的 Forge 引擎 API**（见下方「引擎改动」），
> **不能**用官方原版 Forge 引擎运行本系列卡牌（会报错）。
>
> **想直接玩**：下载 [Releases](https://github.com/LiZiyu153/Card-Forge-GBF/releases) 里的**便携版压缩包**，
> 解压即玩，不需要编译、也不需要安装官方 Forge（见「快速开始」）。
> **想自己编译**：本仓库包含魔改引擎完整源码（`forge-src/`），见「安装（从源码编译）」章节。

本项目仍然在不断更新，欢迎游玩体验并给出建议。

## 内容总览

### 目前的内容

1. 第一轮设计：添加了Mythic Rare‌牌7张、Rare牌20张、Uncommon牌36张、Common牌60张共计123张卡牌。
2. 十二神将补充包：添加了Mythic Rare1张、Rare牌12张共计14张卡牌。
3. 第二轮设计：添加了六龙系列Mythic Rare‌牌6张、其他Rare牌18张、Uncommon牌36张、Common牌60张共计120张卡牌。

当前系列注册卡牌 **256 张**（Mythic 14 / Rare 50 / Uncommon 72 / Common 120），另有 18 个 GBF 衍生物脚本。

### 计划更新

1. 以后会逐步完成第三轮设计，将系列牌数量扩增至400+。
2. 十天众与十贤者补充包
3. 各种稀有度的地牌
4. 职业
5. 召唤石（可能以亲缘瞬间~星晶兽的形式设计）
6. 有灵感了随时加（doge）

## 目录结构

| 路径 | 说明 |
|---|---|
| `res/cardsfolder/<letter>/` | 卡牌脚本（每卡一个 `.txt`，按卡名首字母分目录） |
| `res/cardsfolder/cardsfolder.zip` | Forge 官方卡牌脚本（随仓库分发，保证完整可玩） |
| `res/tokenscripts/` | 衍生物脚本（官方 832 + GBF 18） |
| `res/editions/` | 系列定义（含 `Granblue Fantasy.txt`） |
| `res/lists/TypeLists.txt` | 注册了本系列自定义种族（Draph / Erune / Harvin / Primal等）的类型表 |
| `res/blockdata/` | 轮抓/环境接入（已注册 `Granblue Fantasy` 系列） |
| `res/gbf-pics/GBF/` | GBF 卡图（Forge 不会自动下载自定义集卡图，随仓库分发；刻意不放在 `res/pics/` 旧版数据目录下，以免触发 Forge 启动时的数据迁移弹窗） |
| `forge-src/` | **魔改后的 Forge 引擎完整源码**（基于官方 tag `forge-2.0.13`，编译产物不入库） |
| `version.txt` | **本地版本标记**（一行版本号，如 `0.0.2.1`；随便携版分发，游戏内更新检查的对比基准） |
| `tests/` | 无头解析/行为测试基建（Java，详见 `tests/README.md`） |
| `tools/` | 一键回归 gate（`run_all_tests.cmd`）与反模式 lint（`lint_gbf.py`） |
| `LICENSE.txt` | GPL-3.0 协议全文 |

## 引擎改动（为什么必须用本仓库的 Forge）

为了让 GBF 卡牌脚本能忠实实现原设计，对 Forge 2.0.13 引擎做了以下 API 扩展（详见 `forge-src` 中的改动，均可经 Maven 重编译）：

- 新增 `DB$ PayCost` 结算期支付节点
- `ManaCostShard` 新增 5 色混合费用 `WUBRG`（`W/U/B/R/G`）
- `StaticAbilityMustTarget` 支持 `ValidTarget$ Card.Self`
- `PumpAllEffect` 新增 `AddColor$` 参数；`RepeatEffect` 防死循环上限
- `AbilityFactory`/`CostAdjustment`/`CardFaceSymbols` 等多处容错修复

## 使用方法

### 快速开始（推荐：开箱即用）

1. 到 [Releases 页面](https://github.com/LiZiyu153/Card-Forge-GBF/releases) 下载最新的**便携版压缩包**（如 `Card-Forge-GBF-Portable-v0.0.1.8.zip`）；
2. 解压到任意目录（路径建议不含中文）；
3. 双击运行压缩包内的 **`安装卡图.cmd`**（只需一次：把 GBF 卡图复制到 Forge 卡图缓存目录）；
4. 双击 **`forge.exe`** 或 **`forge.cmd`** 启动游戏（两者等价；`forge.exe` 通过同目录 `l4j.ini` 自动带上与 `forge.cmd` 相同的内存/UTF-8 参数）；
5. 新建牌组/随便玩玩时选择系列 **Granblue Fantasy (GBF)**。

> 需要 **Java 21 或更高版本**（没有的话去 [adoptium.net](https://adoptium.net) 下载安装）。
> 便携版 = 官方安装基底 + 魔改引擎 jar + 全部卡牌脚本 + GBF 卡图，与当前开发环境完全一致。

### 游戏内更新检查（引擎 P-14/P-15 起）

游戏会在启动时自动检查**本仓库**（`LiZiyu153/Card-Forge-GBF`）的最新 Release tag，
与本地根目录 `version.txt` 记录的版本对比；发现新版本时游戏内会有提示，点「立即更新」
即**自动下载并安装**：游戏会下载最新便携版 zip（约 440MB）、解压到旁目录，重启后自动
切换到新版本目录并启动新版，全程无需手动操作（旧版本目录会自动清理）。也可选「手动下载」
打开 Releases 页面。更新检查与下载**只面向本仓库**，不会再连接官方 Card-Forge 的快照/
发布服务器（官方构建会覆盖魔改引擎、破坏 GBF 卡牌脚本）。

> ⚠️ **发版提醒**：每次发布新版，记得把根目录 `version.txt` 内容改成与 Release tag
> 一致（如 tag `v0.0.2.3` → 文件内容 `0.0.2.3`），该文件随便携版 zip 分发，
> 游戏靠它判断"本地已是最新"（缺失时游戏保持安静，不做提示）。
> 发布包必须用 `tools/make_release_zip.py` 打包（标准 UTF-8 文件名，游戏内自动更新需要）。

### 安装（从源码编译）

#### 1. 编译魔改引擎

本仓库的卡牌脚本依赖魔改 API，**必须**使用 `forge-src/` 源码编译出的引擎：

```bat
cd forge-src
mvn -pl forge-gui-desktop -am -DskipTests package
```

产物：`forge-src/forge-gui-desktop/target/forge-gui-desktop-2.0.13-jar-with-dependencies.jar`
（需要 JDK 与 Maven；构建约 16 分钟）

#### 2. 准备 Forge 安装目录

准备一个 `forge-installer-2.0.13` 安装目录（或任何可用的 Forge 2.0.13 安装），
用上一步编译出的 jar **覆盖**安装目录中的同名 jar。

#### 3. 合并资源

将本仓库 `res/` 下的内容合并进 Forge 安装目录的 `res/`（覆盖/合并同名目录）：

- `cardsfolder/` → 卡牌脚本（GBF + 官方）
- `tokenscripts/` → 衍生物脚本
- `editions/` → 系列定义
- `lists/` → 自定义种族注册（**注意：这是整个官方类型表 + 本系列追加项，请保留本仓库版本**）
- `blockdata/` → 轮抓/环境接入

#### 4. 放置卡图

Forge 不会自动下载自定义集（CUSTOM_SET）的卡图，需要手动放置：

将本仓库 `res/gbf-pics/GBF/` 整个文件夹复制到 Forge 的卡图缓存目录：

```
Windows: %LOCALAPPDATA%\Forge\Cache\pics\cards\GBF\
其他:    ~/.forge/cache/pics/cards/GBF/
```

#### 5. 启动

启动 Forge，即可在牌库编辑器/系列列表中找到 `Granblue Fantasy` 系列。

> 本系列轮抓使用 UNH 作为基本地牌集，已按此注册。

### 运行测试

开发环境需使用 Forge 安装目录的 fat jar 作 classpath（详见 `tests/README.md`）。一键回归：

```bat
tools\run_all_tests.cmd
```

- `-Lint`：只跑反模式 lint + 静态验证（快速档）
- `-Quick`：只跑解析测试
- `-Card "卡名"`：只测单卡

## 我也想写一张GBF卡？

1. 在 `res/cardsfolder/<首字母>/` 编写卡牌脚本（Forge 脚本语法）。
2. 在 `res/editions/Granblue Fantasy.txt` 的 `[cards]` 注册卡牌（新衍生物同步注册 `[tokens]`）。
3. 运行 `tools\run_all_tests.cmd` 全量回归（反模式 lint + 编译 + 全量解析 + 行为测试 + 静态验证），全部通过后提交。

## ISSUE

如果你遇到了报错或bug，或者觉得卡牌设计不平衡，或者有自己的设计等等，只要有想法，欢迎留下ISSUE。

## 许可证

本项目遵循 **GPL-3.0** 协议（见 `LICENSE.txt`），与上游 Card-Forge 引擎一致。使用、修改与分发请遵守 GPL-3.0 条款。

## 致谢

- [Card-Forge / forge](https://github.com/Card-Forge/forge) —— 上游开源万智牌引擎（GPL-3.0）
- 卡牌设计灵感来自《碧蓝幻想》（Granblue Fantasy）系列
- 卡图由MSE制作，卡面来自GBF本家，少部分背景图（如十二神将）经过AI重绘扩大分辨率
- 我有智力障碍，所有脚本均由deepseek编写，这蓝色大肥鱼经常白吃我token不好好修bug
