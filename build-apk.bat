@echo off
chcp 65001 >nul 2>&1
setlocal EnableDelayedExpansion

rem ##########################################################################
rem  通知过滤APP —— Windows 一键构建 APK 脚本
rem
rem  用法（直接双击即可，也可以在命令行里传参数）：
rem    build-apk.bat              等价于 build-apk.bat release
rem    build-apk.bat release      构建「已签名、可直接安装」的 release APK（默认）
rem    build-apk.bat debug        构建 debug APK
rem    build-apk.bat install      构建 release APK 后用 adb 安装到已连接的手机
rem
rem  产物统一复制到：<项目根>\outputs\
rem  完整说明见 README.md 的「构建」一节，工程细节见 docs/需求文档.md §7
rem ##########################################################################

rem ============================== 基础路径 ==============================
rem 项目根目录取脚本自身所在目录（支持中文路径，不依赖当前工作目录）
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

rem ============================== 环境变量 ==============================
set "JAVA_HOME=E:\Develop\Java\jdk-17"
set "ANDROID_HOME=E:\Develop\Android\Sdk"
set "ANDROID_SDK_ROOT=E:\Develop\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

set "GRADLEW=%PROJECT_DIR%\gradlew.bat"
set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "LOCAL_PROPERTIES=%PROJECT_DIR%\local.properties"
set "KEYSTORE=%PROJECT_DIR%\keystore\nf-release.jks"
set "OUTPUT_DIR=%PROJECT_DIR%\outputs"

rem 产物文件名里的版本号（改版本时同步 app/build.gradle.kts 的 versionName）
set "VERSION_TAG=v13.1"

rem 签名口令**不写死在仓库里**：优先从 local.properties（已 gitignore）读取，
rem 读不到就用占位默认值。Gradle 侧按同一口径读取（见 app/build.gradle.kts 顶部）。
set "KEYSTORE_PASS=changeme"
set "KEY_ALIAS=nfkey"
set "KEY_PASS="
if exist "%LOCAL_PROPERTIES%" (
  for /f "tokens=1,2 delims==" %%a in ('findstr /b /c:"nf.store.pass=" "%LOCAL_PROPERTIES%"') do set "KEYSTORE_PASS=%%b"
  for /f "tokens=1,2 delims==" %%a in ('findstr /b /c:"nf.key.alias=" "%LOCAL_PROPERTIES%"') do set "KEY_ALIAS=%%b"
  for /f "tokens=1,2 delims==" %%a in ('findstr /b /c:"nf.key.pass=" "%LOCAL_PROPERTIES%"') do set "KEY_PASS=%%b"
)
if not defined KEY_PASS set "KEY_PASS=%KEYSTORE_PASS%"

rem ============================== 参数解析 ==============================
set "CMD=%~1"
if "%CMD%"=="" set "CMD=release"
if /i "%CMD%"=="release" goto MODE_RELEASE
if /i "%CMD%"=="debug" goto MODE_DEBUG
if /i "%CMD%"=="install" goto MODE_INSTALL
goto USAGE

:MODE_RELEASE
set "FLAVOR=release"
set "GRADLE_TASK=assembleRelease"
set "SRC_APK=%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk"
set "OUT_APK=%OUTPUT_DIR%\notification-filter-%VERSION_TAG%-release.apk"
set "DO_INSTALL=0"
goto PREFLIGHT

:MODE_DEBUG
set "FLAVOR=debug"
set "GRADLE_TASK=assembleDebug"
set "SRC_APK=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk"
set "OUT_APK=%OUTPUT_DIR%\notification-filter-%VERSION_TAG%-debug.apk"
set "DO_INSTALL=0"
goto PREFLIGHT

:MODE_INSTALL
set "FLAVOR=release"
set "GRADLE_TASK=assembleRelease"
set "SRC_APK=%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk"
set "OUT_APK=%OUTPUT_DIR%\notification-filter-%VERSION_TAG%-release.apk"
set "DO_INSTALL=1"
goto PREFLIGHT

:USAGE
echo.
echo [错误] 无法识别的参数：%CMD%
echo.
echo 用法：
echo   %~nx0             构建已签名 release APK（默认）
echo   %~nx0 release     构建已签名 release APK
echo   %~nx0 debug       构建 debug APK
echo   %~nx0 install     构建 release APK 并 adb 安装到手机
echo.
echo 按任意键退出...
pause >nul
exit /b 2

rem ============================== 环境预检 ==============================
:PREFLIGHT
echo.
echo ==========================================================================
echo   通知过滤APP  一键构建
echo   工程目录：%PROJECT_DIR%
echo   构建模式：%FLAVOR%         Gradle 任务：%GRADLE_TASK%
echo ==========================================================================
echo.

echo [1/5] 检查 JDK ...
if not exist "%JAVA_HOME%\bin\java.exe" goto ERR_JDK
if not exist "%KEYTOOL%" goto ERR_JDK
echo        正常：%JAVA_HOME%

echo [2/5] 检查 Android SDK ...
if not exist "%ANDROID_HOME%" goto ERR_SDK
if not exist "%ANDROID_HOME%\platforms" goto ERR_SDK
if not exist "%ANDROID_HOME%\build-tools" goto ERR_SDK
echo        正常：%ANDROID_HOME%
if not exist "%GRADLEW%" goto ERR_GRADLEW

echo [3/5] 写入 local.properties ...
> "%LOCAL_PROPERTIES%" echo sdk.dir=E:\\Develop\\Android\\Sdk
>> "%LOCAL_PROPERTIES%" echo nf.store.pass=%KEYSTORE_PASS%
>> "%LOCAL_PROPERTIES%" echo nf.key.alias=%KEY_ALIAS%
>> "%LOCAL_PROPERTIES%" echo nf.key.pass=%KEY_PASS%
echo        已写入：%LOCAL_PROPERTIES%

echo [4/5] 准备输出目录与发布签名密钥 ...
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
if not exist "%OUTPUT_DIR%" goto ERR_OUTPUT
if exist "%KEYSTORE%" goto KEYSTORE_OK
echo        未找到签名密钥，正在自动创建 %KEYSTORE% ...
"%KEYTOOL%" -genkeypair -v -keystore "%KEYSTORE%" -storetype JKS -alias %KEY_ALIAS% -keyalg RSA -keysize 2048 -validity 10950 -storepass %KEYSTORE_PASS% -keypass %KEY_PASS% -dname "CN=nf, OU=local, O=local, L=Local, S=Local, C=CN"
if errorlevel 1 goto ERR_KEYSTORE
:KEYSTORE_OK
echo        正常：%KEYSTORE%

rem ============================== 执行构建 ==============================
echo [5/5] 开始 Gradle 构建 ...
echo.
echo   提示：本机第一次构建需要联网下载 Gradle 8.9 与全部依赖，
echo        体积约数百 MB，耗时几分钟到十几分钟，请保持网络畅通并耐心等待。
echo        公司 / 校园网需要代理时，请在 gradle.properties 末尾追加：
echo          systemProp.https.proxyHost=你的代理地址
echo          systemProp.https.proxyPort=你的代理端口
echo.

call "%GRADLEW%" %GRADLE_TASK% --no-daemon
if errorlevel 1 goto ERR_BUILD

rem ============================== 收集产物 ==============================
if not exist "%SRC_APK%" goto ERR_NOAPK
copy /Y "%SRC_APK%" "%OUT_APK%" >nul
if errorlevel 1 goto ERR_COPY

set "APK_SIZE=0"
for %%F in ("%OUT_APK%") do set "APK_SIZE=%%~zF"
set /a "APK_KB=APK_SIZE/1024"

echo.
echo ==========================================================================
echo   构建成功！
echo   APK 绝对路径：%OUT_APK%
echo   APK 文件大小：%APK_SIZE% 字节（约 %APK_KB% KB）
echo ==========================================================================

if "%DO_INSTALL%"=="0" goto DONE
if not exist "%ADB%" goto ERR_ADB

echo.
echo 正在安装到已连接的设备 ...
"%ADB%" devices
"%ADB%" install -r "%OUT_APK%"
if errorlevel 1 goto ERR_INSTALL
echo        安装完成。
goto DONE

rem ============================== 成功退出 ==============================
:DONE
echo.
echo 全部完成。
echo.
pause
exit /b 0

rem ============================== 错误处理 ==============================
:ERR_JDK
echo.
echo [失败] 找不到 JDK，或者 JAVA_HOME 指向错误。
echo        当前 JAVA_HOME = %JAVA_HOME%
echo        请确认该目录存在，并且里面有 bin\java.exe 与 bin\keytool.exe。
echo        若你的 JDK 装在别处，请用记事本打开本脚本，修改第 24 行 JAVA_HOME 的值。
echo.
pause
exit /b 1

:ERR_SDK
echo.
echo [失败] 找不到 Android SDK，或者 ANDROID_HOME 指向错误。
echo        当前 ANDROID_HOME = %ANDROID_HOME%
echo        请确认该目录存在且下面有 platforms / build-tools / platform-tools 三个子目录。
echo        可以在 Android Studio 里查看真实路径：
echo          File - Settings - Android SDK - Android SDK Location
echo        确认后用记事本打开本脚本，修改第 25 行 ANDROID_HOME 的值。
echo.
pause
exit /b 1

:ERR_GRADLEW
echo.
echo [失败] 工程目录中找不到 gradlew.bat。
echo        期望位置：%GRADLEW%
echo        请确认没有误删该文件；必要时可用 Android Studio 重新打开工程让其自动修复。
echo.
pause
exit /b 1

:ERR_OUTPUT
echo.
echo [失败] 无法创建输出目录：%OUTPUT_DIR%
echo        请检查磁盘空间与该目录的写入权限。
echo.
pause
exit /b 1

:ERR_KEYSTORE
echo.
echo [失败] 自动生成签名密钥失败，请查看上方 keytool 的输出。
echo        你也可以手动用 keytool 生成 keystore\nf-release.jks，命令见 docs/需求文档.md 第 7 节。
echo.
pause
exit /b 1

:ERR_BUILD
echo.
echo [失败] Gradle 构建失败。
echo        请向上滚动窗口，查看 Gradle 打印的具体报错信息。
echo        最常见的原因：
echo          1) 网络问题 —— 首次构建需要联网下载 Gradle 8.9 和全部依赖；
echo             如需代理，在 gradle.properties 里加 systemProp.https.proxyHost / Port。
echo          2) SDK 组件缺失 —— 用 Android Studio 的 SDK Manager
echo             安装 Android SDK Platform 34 与 Build-Tools 34.0.0。
echo          3) 中文乱码 —— 命令行窗口编码问题，一般不会影响构建结果。
echo        详细排查清单见 README.md 的「构建」一节。
echo.
pause
exit /b 1

:ERR_NOAPK
echo.
echo [失败] Gradle 报告成功，但没有找到预期的 APK 文件：
echo        %SRC_APK%
echo        这通常说明构建被跳过或产物路径变更，请回到 Android Studio 执行一次
echo        Build - Clean Project 后再重试本脚本。
echo.
pause
exit /b 1

:ERR_COPY
echo.
echo [失败] APK 复制失败：无法写入 %OUT_APK%
echo        请检查目标磁盘空间，或该 APK 是否正被其他程序占用。
echo.
pause
exit /b 1

:ERR_ADB
echo.
echo [失败] 找不到 adb：%ADB%
echo        请在 Android Studio 的 SDK Manager 里安装 Android SDK Platform-Tools。
echo.
pause
exit /b 1

:ERR_INSTALL
echo.
echo [失败] adb 安装失败。请检查：
echo        1) 手机已用 USB 线连接电脑，并已开启「USB 调试」；
echo        2) 手机弹窗中点选了「允许 USB 调试」；
echo        3) 上方 adb devices 列表里能看到你的设备（不能显示 unauthorized）。
echo        也可以手动执行：adb install -r "%OUT_APK%"
echo.
pause
exit /b 1
