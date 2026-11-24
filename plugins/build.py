from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(__file__).resolve().parent
packages = ("gain", "audio-spectrum")
parser = ArgumentParser(description="Build the standard Radiola plugin packages.")
parser.add_argument("package", nargs="?", choices=packages, help="Build just one plugin.")
selected = parser.parse_args().package

for package in (selected,) if selected else packages:
    directory = root / package
    with ZipFile(directory / f"{package}.radiolaplugin", "w", ZIP_DEFLATED) as archive:
        for entry in sorted((directory / "src").iterdir()):
            if entry.is_file():
                archive.write(entry, entry.name)
