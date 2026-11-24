"""Build the optional FT8 package from a built checkout of the pinned ft8ts source."""
import subprocess
import sys
from pathlib import Path
from shutil import copyfile, which
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile

PIN = "c4d1c10e65f6213f938e4781fec66232286ed569"
root = Path(__file__).resolve().parent
source = root / "src"
upstream = Path(sys.argv[1]).resolve()
revision = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
if revision != PIN:
    raise SystemExit(f"Expected ft8ts revision {PIN}")

with TemporaryDirectory() as temp:
    entry = Path(temp) / "entry.mjs"
    bundle = Path(temp) / "decoder.js"
    (entry.parent / "dist").mkdir()
    copyfile(upstream / "dist/ft8ts.mjs", entry.parent / "dist/ft8ts.mjs")
    copyfile(source / "decoder-entry.mjs", entry)
    node = which("node")
    cli = upstream / "node_modules/rollup/dist/bin/rollup"
    subprocess.run([node, str(cli), str(entry), "--format", "iife", "--name", "FtxBench",
                    "--file", str(bundle)], check=True)
    worker = bundle.read_bytes() + b"\n" + (source / "worker-entry.js").read_bytes()
    with ZipFile(root / "ft8.radiolaplugin", "w", ZIP_DEFLATED) as archive:
        for name in ("manifest.json", "index.html", "common.css", "view.js"):
            archive.write(source / name, name)
        archive.write(upstream / "LICENSE", "LICENSE")
        archive.writestr("worker.js", worker)
