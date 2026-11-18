#!/usr/bin/env python3
"""Record the exact patched Miuix build inputs for metadata-free Fleet snapshots."""
from pathlib import Path
import hashlib
import subprocess
root = Path(__file__).resolve().parents[1]
source = root / 'third_party/miuix'
files = subprocess.check_output(['git', '-C', str(source), 'ls-files', '-z']).decode().split('\0')
# Fleet excludes these generated/local/credential inputs. None is a source dependency.
excluded = {'.git', '.gradle', '.kotlin', 'build', 'node_modules', 'dist', '.idea', '.cxx', '.gradle-dist'}
paths = sorted(p for p in files if p and (source / p).is_file()
               and not any(x in excluded for x in Path(p).parts)
               and Path(p).name != 'local.properties')
text = '# Patched Miuix 9d4b9c85c18707c7da12cf8c497ea57955fcf81e; regenerate with scripts/miuix-source-manifest.py\n'
text += ''.join(hashlib.sha256((source/p).read_bytes()).hexdigest() + '  ' + p + '\n' for p in paths)
(root/'third_party/miuix-source.sha256').write_text(text)
