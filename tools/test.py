#!/usr/bin/env python3
"""Run device-independent tests: WiFi/dumpsys parsing, outage merge-window logic, ICMP wire format.

Unlike a plugin with no android.* imports, IcmpEchoSource.java uses android.system.Os and friends,
which aren't part of a plain JDK — the whole source tree needs android.jar on the compile classpath
even for this "no root, no network, no device" logic-only test run, not just the full build.
"""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')


def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name


sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
platform = android_platform(sdk_root, os.environ.get('ANDROID_PLATFORM'))

sources = [
    *sorted((root / 'sdk/src').rglob('*.java')),
    *sorted((root / 'src').rglob('*.java')),
    *sorted((root / 'test').rglob('*.java')),
]
with tempfile.TemporaryDirectory(prefix='network-diagnostics-test-') as directory:
    subprocess.run(
        [tool('javac'), '--release', '8', '-cp', str(platform), '-d', directory, *map(str, sources)],
        check=True,
    )
    subprocess.run([tool('java'), '-ea', '-cp', directory, 'NetworkMathTest'], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', directory, 'NetworkEntitiesTest'], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)
