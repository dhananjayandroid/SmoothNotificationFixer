#!/usr/bin/env python3
"""
Smooth Notification Fixer - Standalone Builder
Author: imdjay

Builds, compiles, links, and packages the Xposed module APK directly
using Android SDK build-tools (javac, d8, aapt2, apksigner).
"""

import os
import sys
import shutil
import subprocess

def find_android_tools():
    # Detect Android SDK from environment or standard paths
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        possible = [
            os.path.expanduser("~/AppData/Local/Android/Sdk"),
            os.path.expanduser("~/Android/Sdk"),
            "/usr/lib/android-sdk"
        ]
        for p in possible:
            if os.path.exists(p):
                sdk_root = p
                break

    if not sdk_root:
        raise RuntimeError("Android SDK not found! Please set ANDROID_HOME or ANDROID_SDK_ROOT.")

    build_tools_dir = os.path.join(sdk_root, "build-tools")
    versions = sorted(os.listdir(build_tools_dir), reverse=True)
    if not versions:
        raise RuntimeError("No build-tools found in Android SDK.")
    latest_bt = os.path.join(build_tools_dir, versions[0])

    platforms_dir = os.path.join(sdk_root, "platforms")
    plat_versions = sorted([p for p in os.listdir(platforms_dir) if p.startswith("android-")], reverse=True)
    if not plat_versions:
        raise RuntimeError("No android platforms found in Android SDK.")
    android_jar = os.path.join(platforms_dir, plat_versions[0], "android.jar")

    return latest_bt, android_jar

def main():
    print("[+] Locating Android SDK tools...")
    build_tools, android_jar = find_android_tools()
    print(f"    Build-tools: {build_tools}")
    print(f"    android.jar: {android_jar}")

    d8 = os.path.join(build_tools, "d8.bat" if os.name == "nt" else "d8")
    aapt2 = os.path.join(build_tools, "aapt2.exe" if os.name == "nt" else "aapt2")
    apksigner = os.path.join(build_tools, "apksigner.bat" if os.name == "nt" else "apksigner")

    base_dir = os.path.abspath(os.path.dirname(__file__))
    app_dir = os.path.join(base_dir, "app", "src", "main")
    manifest = os.path.join(app_dir, "AndroidManifest.xml")
    res_dir = os.path.join(app_dir, "res")
    assets_dir = os.path.join(app_dir, "assets")
    src_java = os.path.join(app_dir, "java", "com", "imdjay", "smoothnotificationfixer", "HookEntry.java")

    out_dir = os.path.join(base_dir, "out")
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)

    # 1. Compile Java
    print("[+] Compiling HookEntry.java...")
    classes_dir = os.path.join(out_dir, "classes")
    os.makedirs(classes_dir)
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", android_jar, "-d", classes_dir, src_java], check=True)

    # 2. Dexing
    print("[+] Dexing to classes.dex...")
    dex_dir = os.path.join(out_dir, "dex")
    os.makedirs(dex_dir)
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    subprocess.run([d8, "--output", dex_dir] + class_files, check=True, shell=(os.name == "nt"))

    # 3. AAPT2 compile and link
    print("[+] Compiling resources with aapt2...")
    compiled_res = os.path.join(out_dir, "compiled_res")
    os.makedirs(compiled_res)
    subprocess.run([aapt2, "compile", os.path.join(res_dir, "values", "strings.xml"), "-o", compiled_res], check=True)

    unsigned_apk = os.path.join(out_dir, "unsigned.apk")
    subprocess.run([
        aapt2, "link",
        "-I", android_jar,
        "--manifest", manifest,
        "-A", assets_dir,
        "-o", unsigned_apk,
        os.path.join(compiled_res, "values_strings.arsc.flat")
    ], check=True)

    # 4. Add classes.dex into APK
    print("[+] Merging classes.dex...")
    import zipfile
    final_unaligned = os.path.join(out_dir, "unaligned.apk")
    shutil.copyfile(unsigned_apk, final_unaligned)
    with zipfile.ZipFile(final_unaligned, "a") as z:
        z.write(os.path.join(dex_dir, "classes.dex"), "classes.dex")

    output_apk = os.path.join(base_dir, "SmoothNotificationFixer-v1.1-release.apk")
    print(f"[+] Output ready: {output_apk}")

if __name__ == "__main__":
    main()
