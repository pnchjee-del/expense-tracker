$ErrorActionPreference = "Stop"

$JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$env:PATH"

$SDK = "$env:LOCALAPPDATA\Android\Sdk"
$BT = "$SDK\build-tools\36.0.0"
$ANDROID_JAR = "$SDK\platforms\android-35\android.jar"

Write-Host "Cleaning build directories..."
if (Test-Path "android_app\build\obj") { Remove-Item "android_app\build\obj" -Recurse -Force }
if (Test-Path "android_app\build\dex") { Remove-Item "android_app\build\dex" -Recurse -Force }
if (Test-Path "android_app\build\gen") { Remove-Item "android_app\build\gen" -Recurse -Force }

New-Item -ItemType Directory -Force -Path "android_app\build\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "android_app\build\obj" | Out-Null
New-Item -ItemType Directory -Force -Path "android_app\build\dex" | Out-Null

Write-Host "1. Compiling resources..."
& "$BT\aapt2.exe" compile --dir "android_app\res" -o "android_app\build\resources.zip"

Write-Host "2. Linking resources and assets..."
& "$BT\aapt2.exe" link -o "android_app\build\unaligned.apk" `
    -I "$ANDROID_JAR" `
    --manifest "android_app\AndroidManifest.xml" `
    -A "android_app\assets" `
    --java "android_app\build\gen" `
    "android_app\build\resources.zip"

Write-Host "3. Compiling Java sources..."
$javaSources = Get-ChildItem -Path "android_app\src", "android_app\build\gen" -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
& "$JAVA_HOME\bin\javac.exe" --release 8 `
    -classpath "$ANDROID_JAR" `
    -d "android_app\build\obj" `
    $javaSources

Write-Host "4. Converting classes to DEX..."
$classFiles = Get-ChildItem -Path "android_app\build\obj" -Filter "*.class" -Recurse | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --lib "$ANDROID_JAR" --output "android_app\build\dex" $classFiles

Write-Host "5. Adding DEX to APK..."
python -c "import zipfile; z = zipfile.ZipFile('android_app/build/unaligned.apk', 'a', zipfile.ZIP_DEFLATED); z.write('android_app/build/dex/classes.dex', 'classes.dex'); z.close()"

Write-Host "6. Zip-aligning APK..."
if (Test-Path "android_app\build\aligned.apk") { Remove-Item "android_app\build\aligned.apk" -Force }
& "$BT\zipalign.exe" -p -f 4 "android_app\build\unaligned.apk" "android_app\build\aligned.apk"

Write-Host "7. Signing APK with release keystore..."
& "$BT\apksigner.bat" sign `
    --ks "android_app\build\release.keystore" `
    --ks-key-alias expensetracker `
    --ks-pass pass:android123 `
    --key-pass pass:android123 `
    --out "ExpenseTracker.apk" `
    "android_app\build\aligned.apk"

Write-Host "8. Generating update.apk and publishing..."
Copy-Item "ExpenseTracker.apk" "update.apk" -Force
Copy-Item "ExpenseTracker.apk" "static\ExpenseTracker.apk" -Force
Copy-Item "ExpenseTracker.apk" "static\update.apk" -Force

Write-Host "9. Verifying signatures..."
& "$BT\apksigner.bat" verify -v "ExpenseTracker.apk"
& "$BT\apksigner.bat" verify -v "update.apk"

Write-Host "Build complete! APKs generated:"
Get-Item "ExpenseTracker.apk", "update.apk", "static\update.apk" | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
