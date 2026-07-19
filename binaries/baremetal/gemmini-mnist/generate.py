import random
import subprocess
from pathlib import Path
from typing import List
import argparse

NUM_IMAGES = 20
NUM_CLASSES = 10

IMAGE_PATHS_FN = "image_paths.txt"
LABELS_FN = "labels.bin"
MNIST_FT = ".bin"
MNIST_DIR = "mnist_samples"
DATA_DIR = "data"

this_dir: Path = Path(__file__).parent
mnist_dir = this_dir / MNIST_DIR
data_dir = this_dir / DATA_DIR

image_paths_path = data_dir / IMAGE_PATHS_FN
labels_path = data_dir / LABELS_FN


def copy_file(src: Path, dest: Path):
    with src.open("rb") as f:
        with dest.open("wb") as ff:
            ff.write(f.read())


def generate_new():
    assert mnist_dir.exists(), f"MNIST directory does not exist: {mnist_dir}"
    data_dir.mkdir(exist_ok=True, parents=True)

    # Clear data dir
    for file in data_dir.glob("*"):
        file.unlink()

    for digit in range(NUM_CLASSES):
        digit_dir = mnist_dir / str(digit)
        assert digit_dir.exists(), f"Missing digit dir: {digit_dir}"
        images_path = list(digit_dir.glob(f"*{MNIST_FT}"))
        assert len(images_path) >= NUM_IMAGES, f"Too few images in {digit_dir}"
        random.shuffle(images_path)
        images_path: List[Path] = images_path[:NUM_IMAGES]
        with image_paths_path.open("a") as f:
            with labels_path.open("ab") as ff:
                for image_path in images_path:
                    image_name = image_path.name
                    copy_file(image_path, data_dir / image_name)
                    f.write(str(image_name) + "\n")
                    ff.write(digit.to_bytes(1, byteorder="little"))

    with image_paths_path.open() as f:
        image_paths = f.readlines()
    with labels_path.open("rb") as f:
        labels = f.read()
    assert len(image_paths) == len(labels), \
        f"Number of images and labels do not match: {len(image_paths)} vs {len(labels)}"

    print("Done")


def main():
    parser = argparse.ArgumentParser(description="Generate MNIST image/label dataset for baremetal inference.")
    parser.add_argument("--new", action="store_true", help="Generate a new set of MNIST images and labels. THIS WILL OVERWRITE EXISTING DATA.")
    args = parser.parse_args()

    if args.new:
        generate_new()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()