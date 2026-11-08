"""Fetch pinned local-AI build tools, llama.cpp source, and the bundled small model."""
from pathlib import Path
import concurrent.futures
import hashlib
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import time

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tooling'
SDK = TOOLS / 'android-sdk'
SOURCE_COMMIT = 'bb4caa7540188872173c44d161602d9271386413'
SOURCE_SHA = '5c588d37f6d6c6d25b142b5003cff2ab6b94ed676cdf56638d56836182c909aa'
MODEL_REVISION = '09816acd5d99df7be770d85ea30822623dab342c'
MODEL_NAME = 'SmolLM2-135M-Instruct-Q4_0.gguf'
MODEL_SHA = 'bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03'
TOOLS.mkdir(exist_ok=True)

def download(url, target, checksum=None, algorithm='sha256', size=None):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        print('Downloading', target.name, flush=True)
        if size and size > 150 * 1024 * 1024:
            ranged_download(url, target, size)
        else:
            request = urllib.request.Request(url, headers={'User-Agent': 'JARVIS-build/1.1.0'})
            partial = target.with_name(target.name + '.part')
            with urllib.request.urlopen(request, timeout=120) as source, partial.open('wb') as dest:
                shutil.copyfileobj(source, dest, 1024 * 1024)
            partial.replace(target)
    if checksum:
        digest = hashlib.new(algorithm)
        with target.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''): digest.update(block)
        if digest.hexdigest().lower() != checksum.lower(): raise RuntimeError('Checksum mismatch: ' + str(target))
    return target

def ranged_download(url, target, size):
    partial = target.with_name(target.name + '.part')
    sdk_partial = SDK / '.temp/PackageOperation01' / target.name
    if sdk_partial.exists() and sdk_partial.stat().st_size > (partial.stat().st_size if partial.exists() else 0):
        shutil.copyfile(sdk_partial, partial)
    if not partial.exists(): partial.touch()
    begin = partial.stat().st_size
    if begin > size: raise RuntimeError('Invalid partial download size')
    folder = TOOLS / 'download-ranges' / target.name
    folder.mkdir(parents=True, exist_ok=True)
    step = 8 * 1024 * 1024
    ranges = [(start, min(start + step, size) - 1) for start in range(begin, size, step)]
    def chunk(bounds):
        start, end = bounds
        file = folder / f'{start}-{end}'
        if file.exists() and file.stat().st_size == end - start + 1: return file
        for attempt in range(4):
            try:
                request = urllib.request.Request(url, headers={'User-Agent': 'JARVIS-build/1.1.0', 'Range': f'bytes={start}-{end}'})
                with urllib.request.urlopen(request, timeout=45) as response:
                    if response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {start}-{end}/'):
                        raise RuntimeError('Server did not honor download range')
                    data = response.read()
                if len(data) != end - start + 1: raise RuntimeError('Incomplete download range')
                file.write_bytes(data)
                print(f'{target.name}: received through {end + 1:,} / {size:,} bytes', flush=True)
                return file
            except Exception:
                if attempt == 3: raise
                time.sleep(1 << attempt)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        files = list(pool.map(chunk, ranges))
    with partial.open('ab') as dest:
        for file in files:
            with file.open('rb') as source: shutil.copyfileobj(source, dest)
    if partial.stat().st_size != size: raise RuntimeError('Assembled download size mismatch')
    partial.replace(target)

def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            if not (destination / item.filename).resolve().is_relative_to(destination.resolve()):
                raise RuntimeError('Archive path escapes extraction directory')
        z.extractall(destination)

def source():
    target = TOOLS / 'llama.cpp'
    if (target / 'include/llama.h').exists(): return
    archive = download('https://github.com/ggml-org/llama.cpp/archive/' + SOURCE_COMMIT + '.zip', TOOLS / ('llama-' + SOURCE_COMMIT + '.zip'), SOURCE_SHA)
    temp = TOOLS / 'local-ai-source-extract'
    extract(archive, temp)
    shutil.move(str(temp / ('llama.cpp-' + SOURCE_COMMIT)), str(target))
    (target / '.jarvis-source-revision').write_text(SOURCE_COMMIT + '\n')
    print('Pinned llama.cpp source ready', flush=True)

def sdk_tools():
    xml = urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60).read()
    root = ET.fromstring(xml)
    for path in ['ndk;28.2.13676358', 'cmake;3.22.1']:
        destination = SDK.joinpath(*path.split(';'))
        if (destination / 'source.properties').exists(): continue
        package = next(p for p in root.findall('remotePackage') if p.attrib.get('path') == path)
        record = next(a for a in package.findall('archives/archive') if a.findtext('host-os') == 'windows')
        complete = record.find('complete')
        url = complete.findtext('url')
        check = complete.find('checksum')
        algorithm = check.attrib.get('type', 'sha1') if check is not None else 'sha1'
        archive = download('https://dl.google.com/android/repository/' + url, TOOLS / url,
                           check.text if check is not None else None, algorithm, int(complete.findtext('size')))
        temp = TOOLS / ('local-ai-sdk-extract-' + path.replace(';', '-'))
        extract(archive, temp)
        content = temp if (temp / 'source.properties').exists() else next(p for p in temp.iterdir() if p.is_dir())
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            backup = TOOLS / ('incomplete-sdk-' + path.replace(';', '-') + '-' + str(int(time.time())))
            if not destination.resolve().is_relative_to(ROOT) or not backup.resolve().is_relative_to(ROOT):
                raise RuntimeError('SDK recovery path escaped workspace')
            shutil.move(str(destination), str(backup))
        shutil.move(str(content), str(destination))
        print(path, 'ready', flush=True)

def model():
    target = ROOT / 'app/src/main/assets/local-ai'
    target.mkdir(parents=True, exist_ok=True)
    url = 'https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/' + MODEL_REVISION + '/' + MODEL_NAME
    download(url, target / MODEL_NAME, MODEL_SHA)
    download('https://www.apache.org/licenses/LICENSE-2.0.txt', target / 'LICENSE')
    (target / 'MODEL.txt').write_text('SmolLM2-135M-Instruct, Q4_0\nPublisher: HuggingFaceTB\nQuantization: bartowski\nLicense: Apache-2.0\nRevision: ' + MODEL_REVISION + '\nSHA-256: ' + MODEL_SHA + '\nSource: ' + url + '\n', encoding='utf-8')
    print('Verified bundled local model ready', flush=True)

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for _ in pool.map(lambda task: task(), [source, sdk_tools, model]): pass
    print('Local AI toolchain and model ready.', flush=True)
