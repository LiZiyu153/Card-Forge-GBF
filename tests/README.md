# GBF 无头测试基建（tests/）

本目录是 GBF 卡牌脚本无头测试的**权威位置**（自 2026-08-14 起随项目版本化；
旧副本 `D:\forge-analysis\tools\` 保留作备份，勿再修改）。

## 运行方式（一键）

```bat
tools\run_all_tests.cmd            :: 全量：编译 + 解析测试 + 行为测试 + 静态验证
tools\run_all_tests.cmd -Quick     :: 只跑解析测试（编译 + GbfParseTest + GbfTokenParseTest）
tools\run_all_tests.cmd -Card "Vikala,Guardian of the North"   :: 只解析指定卡
```

或用 PowerShell：`powershell -ExecutionPolicy Bypass -File tools\run_all_tests.ps1`

> 运行器内部已固定 CWD=安装目录、jar classpath、UTF-8——不要再手敲 java 命令。

## 测试类清单

| 类 | 类型 | 覆盖 |
|---|---|---|
| `GbfParseTest` | 解析 | 全部 136 张 GBF 卡 `CardFactory.getCard(pc, null, 1, null)`（cardId≥0，权威路径；未知 ApiType/TriggerType/静态模式在此抛异常） |
| `GbfTokenParseTest` | 解析 | 6 个 GBF 衍生物脚本经 `TokenDb.getToken(name,"GBF")` → `CardFactory.getCard`（与真实对局造衍生物同路径；GbfParseTest 覆盖不到 tokenscripts） |
| `GbfBehaviorTest` | 行为 | Anthuria 遗言链 + ETB 指示物数学（Greatest/LeastCardManaCost） |
| `GbfArrietTest` | 行为 | Arriet 模式2「+1/+0 与 +0/+1 分给两个不同生物」（Choices$ 方案回归） |
| `GbfLeJardinTest` | 行为 | Le Jardin de Fleurs `ResolvedLimit$ 2` 每回合上限 + 跨回合重置 |
| `GbfAnilaTest` | 行为 | Anila 变羊 + MayPlay（Animate staticAbilities$）+ 离场放逐 + Warp 关键词 |
| `GbfAndiraTest` | 行为 | Andira Prepare 已备法 + 手牌传奇忍术授予 + Baboon Blast 免费施放链 |
| `GbfVajraTest` | 行为 | Vajra//Basara：ETB 衍生物、Dog 指示物链、战斗转化触发、鹏洛客背面结构、+2/+1/0 能力 |
| `GbfVikalaTest` | 行为 | Vikala：ETB Rat 数量、`+Other` 排除自身、牺牲触发、死亡链磨牌选项、XHalf=ceil(X/2) |
| `GbfBigBerthaTest` | 行为 | Big Bertha 条件减费（`CheckSVar$ X GE6` + 对手总防御力 `Count$Valid$CardToughness`）经真实 `CostAdjustment.adjust` 路径断言调整后 CMC |
| `GbfEugenTest` | 行为 | Eugen：TrigBeginCombat 链（打目标生物 1/无目标改打对方牌手 1）+ 真实 Phase 触发路径（`Phase$ BeginCombat | ValidPlayer$ Player` 双方回合战斗阶段都触发，含 `resetActiveTriggers`/`unfreezeStack` 模拟 onPhaseBegin） |
| `GbfPerpetualTailwindTest` | 行为 | Perpetual Tailwind CDA P/T 回归：`Count$ValidGraveyard,Battlefield Enchantment.YouCtrl` 多区域计数（己方坟场结界 + 己方操控结界；对手坟场/对手操控不计）+ `checkStaticAbilities()` 后断言 P/T |
| `GbfWingsTest` | 行为 | Wings Shall Deliver You 第 I 章目标限制回归：`Instant.YouOwn,Sorcery.YouOwn`（+ `Origin$ Graveyard`）只选己方坟场 instant/sorcery，对手坟场排除；旧写法 `YourGraveyard` 匹配不到任何牌（静默失效证明） |
| `GbfFlameArtsTest` | 行为 | Flame Arts X 伤害回归（R38）：去 `DividedAsYouChoose$` 后 X=3 时双/单目标各受 3（原实现选满 X 目标时每目标恰好 1 点） |
| `GbfCerberusTest` | 行为 | Hadean Watchdog,Cerberus 搜索链回归（R38）：搜索到的地变 1/1 黑 Primal Dog（仍为地）；被牺牲的生物不再被 `Defined$ Remembered` 误变形（`ForgetOtherRemembered$ True`） |
| `GbfDiagTest` | 行为 | Batch 3 实测失败修复回归（R40/R41，17 项）：Trilok 可选费用注册 + Charm X1/X3 全模式、Cosmos 结束步链（ChoosePlayer+RememberAmount）、Athena 伤害重定向（Shining Shoal 模板）、Protect 磨牌→选永久物链（引擎 P-12）、Galleon/Fediel 死亡改放逐（Origin$ All）、Yggdrasil 地变仍为地、Europa 法力异能例外（!ManaAbility）、Wamdus 坟场咒语置底+弹+抓（Feather 替换链）+ 抓牌条件（ChosenCard 归属）、Fediel ETB 双方坟场全放逐、Galleon R2 来源区限制（手牌→放逐+指示物/战场→正常死）、Lich 重生费用可付性（SubCounter<1/ANY/CARDNAME>）、Anne token 维持自放逐、Rei {T} 动态灵气化（R41 三修方案 A：站场纯生物 → Animate 转纯 Aura 结附 → 宿主死 704.5m 进坟 → 离场还原形态） |
| `GbfTestBase` | 基类 | 公共样板：FModel 初始化、dev 双人局、makeCard/addToBattlefield/enterBattlefield、playUntilStackClear |

## 新增测试的步骤

1. `public class GbfXxxTest extends GbfTestBase`，main 里先 `init()`。
2. 复用基类 helper；需要"真实触发路径"时用 `enterBattlefield(game, card)` +
   `runTriggersAndClear(game)`；需要"链驱动"时用
   `AbilityFactory.getAbility(card, "SVar名")` + `sa.getTargets().add(target)` +
   `game.getStack().add(sa)` + `playUntilStackClear(game)`。
3. 断言失败打印 `[名称] ... -> FAIL`，全部通过时 main 末尾打印 `ALL PASS` 并
   `System.exit(ok ? 0 : 1)`（运行器按退出码判定）。
4. 在运行器 `$BehaviorTests` 列表注册新类名。

## 关键规则（踩过的坑，勿重复）

- **必须**以安装目录 fat jar 为 classpath（解包 classes 的 `BuildInfo` 版本为
  "GIT"，`getAssetsDir()` 变 `../forge-gui/`，Localizer 找不到语言包）。
- **必须**从安装目录运行（jar 的 `getAssetsDir()` 返回 ""，res/languages 按 CWD
  解析）——运行器已自动 Push-Location，勿手动换目录。
- `CardFactory.getCard` 的卡在 null zone：先 `zone.add(card)` 再 `moveTo`；
  `moveTo(Hand)` 会 copy 卡、原卡留 null zone。
- dev 模式 `changeZone` 只注册外在触发 → 显式 `registerActiveTrigger(card, false)`。
- dev 模式触发源自己移动的 LTB 触发不排队 → 链驱动验证。
- dev 模式 AI 选目标偏好触发源自己 → 用 `sa.getTargets().add(目标)` 指定。
- ImmediateTrigger / 忠诚费用支付 / LKI `Defined$`（TriggeredCardLKICopy、
  TargetedController）/ DFC 背面独立 makeCard / 放逐-转化返回链 —— **dev 无法验证**，
  以官方同构模板为依据并列入 `verification-todo.md` 真实对局验证。
