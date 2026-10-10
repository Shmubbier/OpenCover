# Throwaway spike build: no Gradle, just SDK tools.
$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$jar = "$sdk\platforms\android-37.0\android.jar"
$jbr = "C:\Program Files\Android\Android Studio\jbr\bin"
$env:JAVA_HOME = Split-Path $jbr
$out = "$PSScriptRoot\out"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$out\classes" | Out-Null

& "$jbr\javac.exe" --release 17 -classpath $jar -d "$out\classes" (Get-ChildItem "$PSScriptRoot\src" -Recurse -Filter *.java).FullName
& "$bt\d8.bat" --lib $jar --min-api 34 --output $out (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
& "$bt\aapt2.exe" link -I $jar --manifest "$PSScriptRoot\AndroidManifest.xml" -o "$out\unsigned.apk"
Push-Location $out; & "$jbr\jar.exe" uf unsigned.apk classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"

$ks = "$PSScriptRoot\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jbr\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias debug -keyalg RSA -validity 10000 -dname "CN=debug"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --out "$out\spike.apk" "$out\aligned.apk"
"built $out\spike.apk"
