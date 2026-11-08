"""Provision a project-local Windows Android build toolchain and bundled Vosk model."""
from pathlib import Path
import concurrent.futures
import hashlib
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tooling'
SDK = TOOLS / 'android-sdk'
TOOLS.mkdir(exist_ok=True)

def fetch(url, target, sha256=None):
    target = Path(target)
    if not target.exists():
        print('Downloading', url, flush=True)
        with urllib.request.urlopen(url, timeout=180) as response, target.with_suffix('.part').open('wb') as out:
            shutil.copyfileobj(response, out)
        target.with_suffix('.part').replace(target)
    if sha256 and hashlib.sha256(target.read_bytes()).hexdigest() != sha256:
        raise RuntimeError('Checksum mismatch: ' + str(target))
    return target

def unzip(url, name, destination):
    archive = fetch(url, TOOLS / name)
    if not destination.exists():
        destination.mkdir(parents=True)
        with zipfile.ZipFile(archive) as z:
            z.extractall(destination)

def jdk():
    unzip('https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip', 'jdk.zip', TOOLS / 'jdk')

def gradle():
    unzip('https://services.gradle.org/distributions/gradle-9.3.1-bin.zip', 'gradle.zip', TOOLS / 'gradle')

def sdk():
    raw = urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60).read()
    root = ET.fromstring(raw)
    for path, destination in [('platforms;android-37.0', SDK / 'platforms' / 'android-37.0'),
                              ('build-tools;36.0.0', SDK / 'build-tools' / '36.0.0'),
                              ('platform-tools', SDK / 'platform-tools')]:
        if destination.exists():
            continue
        package = next(p for p in root.findall('remotePackage') if p.attrib.get('path') == path)
        archive = next(a for a in package.findall('archives/archive') if a.findtext('host-os') in (None, 'windows'))
        url = archive.findtext('complete/url')
        temp = TOOLS / ('extract-' + path.replace(';', '-'))
        unzip('https://dl.google.com/android/repository/' + url, url, temp)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(next(temp.iterdir())), str(destination))
    # Android's SDK license hash; license text is retained for review alongside the tools.
    license_element = next(x for x in root if x.tag.endswith('license'))
    text = license_element.text or ''
    (TOOLS / 'ANDROID-SDK-LICENSE.txt').write_text(text, encoding='utf-8')
    (SDK / 'licenses').mkdir(exist_ok=True)
    (SDK / 'licenses' / 'android-sdk-license').write_text('8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n')
    (ROOT / 'local.properties').write_text('sdk.dir=' + SDK.as_posix().replace(':', '\\:') + '\n', newline='\n')

def model():
    target = ROOT / 'app/src/main/assets/model-en-us'
    if target.exists():
        return
    archive = fetch('https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip', TOOLS / 'model.zip')
    temp = TOOLS / 'model'
    temp.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        z.extractall(temp)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(temp / 'vosk-model-small-en-us-0.15'), str(target))
    (target / 'uuid').write_text('vosk-model-small-en-us-0.15-jarvis-v1')
    (ROOT / 'MODEL-SHA256.txt').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  vosk-model-small-en-us-0.15.zip\n')

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(lambda f: f(), [jdk, gradle, sdk, model]):
            pass
    subprocess.run([sys.executable, str(ROOT / 'scripts/provision_local_ai.py')], check=True)
    print('Toolchain, wake model and local AI ready.', flush=True)

