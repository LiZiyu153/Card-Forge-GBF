<#
.SYNOPSIS
    GBF 卡牌脚本一键测试运行器（回归 gate）。

.DESCRIPTION
    依次执行：
      0) 反模式 lint（tools/lint_gbf.py，秒级，任何模式都跑）
      1) 编译 tests/java 下的全部无头测试（javac，jar classpath）
      2) 权威解析测试 GbfParseTest（全部 GBF 卡，cardId>=0）
      3) 衍生物解析测试 GbfTokenParseTest（[tokens] 段 5 个脚本）
      4) 全部行为测试（GbfBehaviorTest / GbfArrietTest / GbfLeJardinTest /
         GbfAnilaTest / GbfAndiraTest / GbfVajraTest / GbfVikalaTest /
         GbfBigBerthaTest / GbfEugenTest / GbfPerpetualTailwindTest）
      5) 静态验证（python D:/forge-analysis/verify_gbf_scripts.py，可跳过）

    内部自动固定：CWD=安装目录（Localizer 依赖）、fat jar classpath、
    -Dfile.encoding=UTF-8。任何一步非零退出码 → 最终退出码非零。

.PARAMETER Card
    只解析指定卡名（透传给 GbfParseTest），用于快速单卡检查。

.PARAMETER Quick
    跳过行为测试与静态验证（只跑编译 + 两个解析测试）。

.PARAMETER Lint
    只跑反模式 lint + 静态验证（日常快速档，秒级；改卡迭代用）。

.PARAMETER SkipStatic
    跳过 python 静态验证。

.PARAMETER NoCompile
    跳过 javac 编译（使用 tests/classes 既有 .class）。

.EXAMPLE
    tools\run_all_tests.ps1
    tools\run_all_tests.ps1 -Card "Vikala, Guardian of the North"
    tools\run_all_tests.ps1 -Quick -SkipStatic
    tools\run_all_tests.ps1 -Lint
#>
param(
    [string]$Card = "",
    [switch]$Quick,
    [switch]$SkipStatic,
    [switch]$NoCompile,
    [switch]$Lint
)

$ErrorActionPreference = 'Stop'
$InstallDir = Split-Path -Parent $PSScriptRoot   # tools/ -> 安装目录
$Jar        = Join-Path $InstallDir 'forge-gui-desktop-2.0.13-jar-with-dependencies.jar'
$TestSrc    = Join-Path $InstallDir 'tests\java'
$TestOut    = Join-Path $InstallDir 'tests\classes'

if (-not (Test-Path $Jar)) {
    Write-Host "[FATAL] jar 不存在: $Jar" -ForegroundColor Red
    exit 2
}

New-Item -ItemType Directory -Force -Path $TestOut | Out-Null
$failCount = 0

function Run-Test {
    param([string]$ClassName, [string[]]$TestArgs = @())
    Push-Location $InstallDir
    try {
        Write-Host "---- $ClassName $($TestArgs -join ' ') ----" -ForegroundColor Cyan
        # 注意：PowerShell 5.1 内联 `-Dfile.encoding=UTF-8` 会被错误拆分（-Dfile 被吞，
        # 主类变成 /encoding=UTF-8），必须用数组 splat 传参（实测 `& java @args` 正常）。
        $javaArgs = @('-Dfile.encoding=UTF-8', '-cp', "$Jar;$TestOut", $ClassName) + $TestArgs
        & java @javaArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[PASS] $ClassName" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] $ClassName (exit=$LASTEXITCODE)" -ForegroundColor Red
            $script:failCount++
        }
    } catch {
        Write-Host "[ERROR] $ClassName : $_" -ForegroundColor Red
        $script:failCount++
    } finally {
        Pop-Location
    }
}

function Run-Lint {
    Push-Location $InstallDir
    try {
        $lintArgs = @('tools/lint_gbf.py', 'res/cardsfolder', 'res/tokenscripts',
                      'res/editions/Granblue Fantasy.txt')
        & python @lintArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[PASS] 反模式 lint" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] 反模式 lint (exit=$LASTEXITCODE)" -ForegroundColor Red
            $script:failCount++
        }
    } catch {
        Write-Host "[ERROR] 反模式 lint : $_" -ForegroundColor Red
        $script:failCount++
    } finally {
        Pop-Location
    }
}

function Run-Static {
    Push-Location $InstallDir
    try {
        # 第 4 参 = 集定义文件：GBF 卡名/衍生脚本名从 [cards]/[tokens] 段派生（新卡自动纳入）
        $verifyArgs = @('D:/forge-analysis/verify_gbf_scripts.py', 'res/cardsfolder',
                        'res/tokenscripts', 'D:/forge-analysis/forge-src',
                        'res/editions/Granblue Fantasy.txt')
        & python @verifyArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[PASS] 静态验证" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] 静态验证 (exit=$LASTEXITCODE)" -ForegroundColor Red
            $script:failCount++
        }
    } catch {
        Write-Host "[ERROR] 静态验证 : $_" -ForegroundColor Red
        $script:failCount++
    } finally {
        Pop-Location
    }
}

Write-Host "================ GBF 回归 gate ================" -ForegroundColor Cyan
Write-Host "安装目录: $InstallDir"
Write-Host "jar     : $(Split-Path -Leaf $Jar)"

# ---- [0/6] 反模式 lint（所有模式都跑；C 表反模式秒级扫描） ----
Write-Host "---- [0/6] 反模式 lint (lint_gbf.py) ----" -ForegroundColor Cyan
Run-Lint

if ($Lint) {
    # ---- 快速档：lint + 静态验证 ----
    if ($SkipStatic) {
        Write-Host "---- 静态验证 (跳过, -SkipStatic) ----"
    } else {
        Write-Host "---- 静态验证 (verify_gbf_scripts.py) ----" -ForegroundColor Cyan
        Run-Static
    }
    Write-Host "================================================" -ForegroundColor Cyan
    if ($failCount -eq 0) {
        Write-Host "LINT PASS: 反模式 lint + 静态验证通过" -ForegroundColor Green
        exit 0
    } else {
        Write-Host "LINT FAIL: $failCount 项失败" -ForegroundColor Red
        exit 1
    }
}

# ---- [1/6] 编译 ----
if (-not $NoCompile) {
    Write-Host "---- [1/6] 编译测试 (javac) ----" -ForegroundColor Cyan
    # 清理旧 .class，避免残留掩盖编译错误
    Get-ChildItem $TestOut -Filter *.class -ErrorAction SilentlyContinue | Remove-Item -Force
    $srcs = @(Get-ChildItem $TestSrc -Filter *.java | ForEach-Object { $_.FullName })
    if ($srcs.Count -eq 0) {
        Write-Host "[FATAL] tests/java 下没有 .java" -ForegroundColor Red
        exit 2
    }
    & javac -encoding UTF-8 -cp "$Jar;$TestOut" -d $TestOut $srcs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] 编译失败" -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] 编译完成 ($($srcs.Count) 个源文件)" -ForegroundColor Green
} else {
    Write-Host "---- [1/6] 编译测试 (跳过, -NoCompile) ----"
}

# ---- [2/6] 权威解析测试 ----
Write-Host "---- [2/6] GbfParseTest ----" -ForegroundColor Cyan
$parseArgs = @()
if ($Card) { $parseArgs += $Card }
Run-Test 'GbfParseTest' $parseArgs

# ---- [3/6] 衍生物解析测试 ----
Write-Host "---- [3/6] GbfTokenParseTest ----" -ForegroundColor Cyan
Run-Test 'GbfTokenParseTest' @()

# ---- [4/6] 行为测试 ----
$behaviorTests = @(
    'GbfBehaviorTest',
    'GbfArrietTest',
    'GbfLeJardinTest',
    'GbfAnilaTest',
    'GbfAndiraTest',
    'GbfVajraTest',
    'GbfVikalaTest',
    'GbfBigBerthaTest',
    'GbfEugenTest',
    'GbfPerpetualTailwindTest',
    'GbfWingsTest',
    'GbfFlameArtsTest',
    'GbfCerberusTest',
    'GbfDiagTest',
    'GbfR45RegressionTest'
)
if ($Quick) {
    Write-Host "---- [4/6] 行为测试 (跳过, -Quick) ----"
} else {
    Write-Host "---- [4/6] 行为测试 ----" -ForegroundColor Cyan
    foreach ($t in $behaviorTests) {
        Run-Test $t @()
    }
}

# ---- [5/6] 静态验证 ----
if ($SkipStatic -or $Quick) {
    Write-Host "---- [5/6] 静态验证 (跳过) ----"
} else {
    Write-Host "---- [5/6] 静态验证 (verify_gbf_scripts.py) ----" -ForegroundColor Cyan
    Run-Static
}

Write-Host "================================================" -ForegroundColor Cyan
if ($failCount -eq 0) {
    Write-Host "GATE PASS: 全部测试通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "GATE FAIL: $failCount 项失败" -ForegroundColor Red
    exit 1
}
