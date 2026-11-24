from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(__file__).resolve().parent
for package in ("gain", "audio-spectrum"):
    with ZipFile(root / f"{package}.radiolaplugin", "w", ZIP_DEFLATED) as archive:
        for file in sorted((root / package).iterdir()):
            if file.is_file():
                archive.writestr(file.name, file.read_bytes())
