#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GBF 卡牌脚本反模式 lint —— AGENTS.md §C 踩坑表的脚本化（秒级快速检查）。

用法:
    python tools/lint_gbf.py res/cardsfolder res/tokenscripts "res/editions/Granblue Fantasy.txt" [--strict-warnings]

- 扫描 res/cardsfolder 下全部 GBF 卡脚本 + editions [tokens] 段声明的 GBF 衍生物脚本。
- ERROR = 确定错误（退出码 1）；WARN = 疑似（默认不影响退出码）。
- 与 D:/forge-analysis/verify_gbf_scripts.py（静态验证：注册/关键字/引用）互补：
  本工具查"写法反模式"，那个查"结构完整性"。
"""
import argparse
import os
import re
import sys

# ---------- Condition$ 白名单（引擎源码核实） ----------
# 静态能力（S:Mode$）合法值 —— StaticAbility.checkConditions（写错静默恒真）
CONDITION_WHITELIST_STATIC = {
    "Threshold", "Hellbent", "Metalcraft", "Delirium", "Ferocious", "Desert",
    "Blessing", "Monarch", "Night", "MaxSpeed", "PlayerTurn", "NotPlayerTurn",
    "ExtraTurn", "FatefulHour",
}
# 异能行（A:SP$/A:AB$/DB$ 子能力）合法值 —— SpellAbilityCondition.setConditions（写错静默忽略）
CONDITION_WHITELIST_SPELL = {
    "Threshold", "Metalcraft", "Delirium", "Hellbent", "Revolt", "Desert", "Blessing",
    "Kicked", "Kicked 1", "Kicked 2", "Surge", "Bargain", "Teamwork", "AltCost",
    "OptionalCost", "Foretold",
}

# valid 限制字符串中的类型前缀（用于"类型+属性缺点号"检查）
TYPE_WORDS = (
    "Creature|Artifact|Enchantment|Land|Instant|Sorcery|Planeswalker|Spell|"
    "Permanent|Card|Player|Token|Battle|Conspiracy|Contraption|Vanguard|Scheme|Plane|Phenomenon"
)


def load_gbf_tokens(editions_file):
    """从 editions 文件 [tokens] 段取 GBF 衍生物脚本名列表。"""
    names = []
    if not editions_file:
        return names
    try:
        with open(editions_file, encoding="utf-8", errors="replace") as f:
            in_tokens = False
            for line in f:
                s = line.strip()
                if s.startswith("["):
                    in_tokens = (s.lower() == "[tokens]")
                    continue
                if in_tokens and s:
                    parts = s.split()
                    if len(parts) >= 2:
                        names.append(parts[1])
    except OSError as e:
        print(f"[lint] 警告: 无法读取 editions 文件 {editions_file}: {e}")
    return names


class Linter:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.token_refs = {}   # 卡文件 -> set(TokenScript$ 名)

    def report(self, fname, lineno, sev, msg):
        (self.errors if sev == "ERROR" else self.warnings).append(
            f"{fname}:{lineno}: [{sev}] {msg}")

    def check_line(self, fname, lineno, line):
        # 1) Condition$：白名单极窄。S:Mode$ 静态行用静态白名单（写错静默恒真）；
        #    其余异能行用 SpellAbilityCondition 白名单（写错静默忽略 → 条件永远不生效）。
        #    注：Condition$ Kicked 在咒语链子能力有效（sa.isKicked），在触发链子能力恒 false（R6）——后者无法静态判定。
        m = re.search(r"Condition\$ (\w+)", line)
        if m:
            if "S:Mode$" in line:
                if m.group(1) not in CONDITION_WHITELIST_STATIC:
                    self.report(fname, lineno, "WARN",
                                f"静态 Condition$ {m.group(1)} 不在白名单（{'/'.join(sorted(CONDITION_WHITELIST_STATIC))}），写错会静默恒真")
            elif not line.strip().startswith("T:Mode$"):
                if m.group(1) not in CONDITION_WHITELIST_SPELL:
                    self.report(fname, lineno, "ERROR",
                                f"异能行 Condition$ {m.group(1)} 不在白名单（{'/'.join(sorted(CONDITION_WHITELIST_SPELL))}），引擎静默忽略 → 条件永远不生效")

        # 2) Phase$ EndOfTurn（应为带空格的 "End of Turn"）
        if re.search(r"Phase\$ EndOfTurn\b", line):
            self.report(fname, lineno, "ERROR", "Phase$ EndOfTurn 无效，应为 Phase$ End of Turn（带空格，否则抛异常）")

        # 3) valid 限制"类型+属性"缺分隔点号（Card.isValid 按第一个 . 切分 → 整串变类型名 → 恒 false）
        #    lookbehind 排除 "Creature.Artifact+YouCtrl" / "Card.Creature+OwnedBy" 等合法写法
        m = re.search(rf"(?<![.\w])(?:{TYPE_WORDS})\+[A-Z]", line)
        if m:
            self.report(fname, lineno, "ERROR",
                        f"valid 限制 '{m.group(0)}' 缺类型/属性分隔点号（应为 <类型>.<属性>，如 Creature.powerGE3；属性间才用 +）")
        m = re.search(rf"(?<![.\w])(?:{TYPE_WORDS})\+[a-z]", line)
        if m:
            self.report(fname, lineno, "WARN",
                        f"valid 限制 '{m.group(0)}' 疑似缺类型/属性分隔点号（小写属性也须点号分隔）")

        # 4) 搜寻牌库的洗牌语义。
        #    凡 Origin$ Library 走 changeHiddenOriginResolve（ChangeZoneEffect L991/L994：
        #    shuffleMandatory 默认 true），引擎在移动前自动洗牌 —— 所有 Tutor 式
        #    "搜寻牌库→（上手/置顶/置底/战场）"均不必写 Shuffle$ True（官方 Mystical/
        #    Worldly/Vampiric Tutor、Three Dreams、Tiamat 均不写；GBF 既有卡亦同）。
        #    只有显式关闭洗牌（Shuffle$ False / NoShuffle$）才偏离规则。
        #    注意 Dig 的 Shuffle 参数另有含义，不在此列。
        if re.search(r"Origin\$ Library", line) \
           and not re.search(r"DB\$ (Dig|Mill)", line) \
           and (re.search(r"Shuffle\$ False", line) or "NoShuffle$" in line):
            self.report(fname, lineno, "WARN", "Origin$ Library 显式关闭洗牌（Shuffle$ False/NoShuffle$）——确认设计确实不洗牌")

        # 5) Phase 类触发缺 TriggerZones$（缺省手牌/坟场也会触发，R24 Mahira 教训）。
        #    其余模式不告警：ChangesZone 由 TriggerChangesZone.correctZones 自动修正；
        #    战斗/事件类触发（Attacks/Taps/DamageDone…）事件只在战场发生，官方同样常省略（采样 48%）。
        if re.match(r"^\s*T:Mode\$ Phase", line) and "TriggerZones$" not in line:
            self.report(fname, lineno, "WARN", "Phase 类触发器缺 TriggerZones$（缺省手牌/坟场也触发，R24 Mahira 教训）；永久物写 TriggerZones$ Battlefield")

        # 6) 裸 Count$Kicked（会 ArrayIndexOutOfBounds 崩溃）
        if re.search(r"Count\$Kicked(?!\.\d)", line):
            self.report(fname, lineno, "ERROR", "裸 Count$Kicked 会崩溃，必须写 Count$Kicked.1.0（或 Count$Kicked.<N>.<M>）")

        # 7) K:CantBeCountered（引擎无此关键字）
        if re.search(r"K:CantBeCountered", line):
            self.report(fname, lineno, "ERROR", "K:CantBeCountered 无效，用 R:Event$ Counter | ValidSA$ Spell | Layer$ CantHappen")

        # 8) DB$ CopySpell（应为 CopySpellAbility）
        if re.search(r"DB\$ CopySpell(?!Ability)", line):
            self.report(fname, lineno, "ERROR", "DB$ CopySpell 无效，用 DB$ CopySpellAbility（复制本咒语用 Defined$ Parent）")

        # 9) MayChooseNewTargets$（应为 MayChooseTarget$）
        if "MayChooseNewTargets$" in line:
            self.report(fname, lineno, "ERROR", "MayChooseNewTargets$ 无效，用 MayChooseTarget$ True")

        # 10) 战斗触发旧写法
        if re.search(r"T:Mode\$ CombatDamageDealt|(?:^|\|)\s*Mode\$ Blocked\b", line):
            self.report(fname, lineno, "ERROR", "战斗触发旧写法：用 T:Mode$ DamageDealtOnce | CombatDamage$ True / T:Mode$ AttackerBlocked")

        # 11) KW$ loses（关键字移除）
        if re.search(r"KW\$ loses", line):
            self.report(fname, lineno, "ERROR", "KW$ loses 无效，用 DB$ Animate | RemoveKeywords$ <关键字>")

        # 12) DB$ Discard 无 Mode$ Choose（白名单：Defined/Hand/Random/TgtChoose/RevealTgtChoose/RevealDiscardAll/RevealYouChoose）
        if re.search(r"DB\$ Discard[^|]*\|[^|]*Mode\$ Choose\b", line):
            self.report(fname, lineno, "ERROR", "DB$ Discard 没有 Mode$ Choose，用 Mode$ TgtChoose/Defined/Hand 等（否则静默不弃牌）")

        # 13) K:Protection: 冒号式（应为 "K:Protection from <色>"）
        if re.search(r"K:Protection:", line):
            self.report(fname, lineno, "ERROR", "K:Protection:<色>: 无效，用 K:Protection from <色>")

        # 14) AddAbility$ 授予的 SVar 值以 A:AB$ 开头（运行时 "no API in ..." 崩溃）
        if re.search(r"SVar:[A-Za-z0-9_]+:A:AB\$", line):
            self.report(fname, lineno, "ERROR", "AddAbility$ 授予的 SVar 值应以 AB$ 开头（不是 A:AB$，后者 parseToMap 解析错位、运行时崩溃）")

        # 15) 牺牲自己 Defined$ Self（应为 SacValid$ Self）
        if re.search(r"DB\$ Sacrifice[^|]*Defined\$ Self", line):
            self.report(fname, lineno, "ERROR", "牺牲自己用 DB$ Sacrifice | SacValid$ Self（Defined$ Self 无效）")

        # 16) DB$ Mill 参数是 NumCards$（不是 Num$）
        if re.search(r"DB\$ Mill[^|]*\|[^|]*Num\$", line):
            self.report(fname, lineno, "ERROR", "DB$ Mill 参数是 NumCards$（不是 Num$）")

        # 17) DB$ Manifest 参数是 Amount$（不是 NumCards$）
        if re.search(r"DB\$ Manifest[^|]*\|[^|]*NumCards\$", line):
            self.report(fname, lineno, "ERROR", "DB$ Manifest 参数是 Amount$（不是 NumCards$）")

        # 18) K:Saga 手写章节（应 K:Chapter）
        if re.search(r"K:Saga:", line):
            self.report(fname, lineno, "ERROR", "K:Saga 手写章节易双倍 lore/章节全触发，用 K:Chapter:N:DBI,DBII,DBIII")

        # 19) SVar 命名为 K（与 K: 关键字混淆；官方教训 R14/R27 的 SVar 共用变体）
        if re.search(r"SVar:K[:$]", line):
            self.report(fname, lineno, "WARN", "SVar 命名为 K 易与 K: 关键字混淆，改名（如 Kicked）")

        # 20) K:Warp 混合费用带斜杠（应为拼接，如 WU）
        if re.search(r"K:Warp:[A-Za-z]+/[A-Za-z]", line):
            self.report(fname, lineno, "WARN", "K:Warp 混合费用用拼接（如 WU），不是 W/U")

        # 21) DB$ ChangeZone（非 ChangeZoneAll）从 Exile → Hand 无 Hidden$ True（静默不做事；
        #     ChangeZoneAll 走 ChangeZoneAllEffect 支持 ChangeType$ 过滤，不需要 Hidden）
        if re.search(r"DB\$ ChangeZone(?!All)[^|]*Origin\$ Exile(?=.*Destination\$ Hand)(?!.*Hidden\$ True)", line):
            self.report(fname, lineno, "ERROR", "DB$ ChangeZone Origin$ Exile → Destination$ Hand 必须带 Hidden$ True（否则走公开区路径静默不做事）")

        # 22) 收集 TokenScript$ 引用（文件末尾统一核对存在性）
        for m in re.finditer(r"TokenScript\$ ([A-Za-z0-9_]+)", line):
            self.token_refs.setdefault(fname, set()).add(m.group(1))

    def check_token_existence(self, tokens_dir):
        for fname in sorted(self.token_refs):
            for n in sorted(self.token_refs[fname]):
                if not os.path.isfile(os.path.join(tokens_dir, n + ".txt")):
                    self.errors.append(
                        f"{fname}: TokenScript$ {n} 在 res/tokenscripts/ 下找不到脚本文件 {n}.txt")


def main():
    ap = argparse.ArgumentParser(description="GBF 卡牌脚本反模式 lint（AGENTS.md §C 脚本化）")
    ap.add_argument("cardsfolder", help="res/cardsfolder 目录")
    ap.add_argument("tokenscripts", help="res/tokenscripts 目录")
    ap.add_argument("editions", nargs="?", default=None, help="res/editions/Granblue Fantasy.txt（取 GBF 衍生物范围）")
    ap.add_argument("--strict-warnings", action="store_true", help="存在 WARN 也返回退出码 1")
    args = ap.parse_args()

    files = []
    for root, _dirs, names in os.walk(args.cardsfolder):
        for n in names:
            if n.endswith(".txt"):
                files.append(os.path.join(root, n))

    gbf_tokens = set(load_gbf_tokens(args.editions))
    if gbf_tokens:
        for n in gbf_tokens:
            p = os.path.join(args.tokenscripts, n + ".txt")
            if os.path.isfile(p):
                files.append(p)
        print(f"[lint] GBF 衍生物范围: {len(gbf_tokens)} 个（来自 editions [tokens]）")
    else:
        print("[lint] 警告: 未能从 editions 取得衍生物范围，只扫描 cardsfolder")

    linter = Linter()
    for f in sorted(files):
        rel = os.path.relpath(f)
        try:
            with open(f, encoding="utf-8", errors="replace") as fh:
                for i, line in enumerate(fh, 1):
                    linter.check_line(rel, i, line.rstrip("\n"))
        except OSError as e:
            print(f"[lint] 无法读取 {rel}: {e}")

    linter.check_token_existence(args.tokenscripts)

    print(f"\n[lint] 扫描 {len(files)} 个文件: {len(linter.errors)} ERROR, {len(linter.warnings)} WARN")
    for e in sorted(linter.errors):
        print(e)
    for w in sorted(linter.warnings):
        print(w)

    sys.exit(1 if (linter.errors or (args.strict_warnings and linter.warnings)) else 0)


if __name__ == "__main__":
    main()
