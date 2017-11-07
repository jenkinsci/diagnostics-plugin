#!/bin/sh -e

dir=$(dirname $0)
dir=$(cd "$dir"; pwd)
for src in "$dir"/*.svg
do
  echo "Processing $(basename "$src")..."
  file=$(basename "$src" | sed -e s/.svg/.png/ )
  for sz in 16 24 32 48 64
  do
    mkdir -p "${dir}/../../src/main/webapp/images/${sz}x${sz}" > /dev/null 2>&1 || true
    #inkscape on mac requires full paths
    dst=$(cd "${dir}/../../src/main/webapp/images/${sz}x${sz}"; pwd)
    dst="$dst/${file}"
    if [ ! -e "$dst" -o "$src" -nt "$dst" ]
    then
      echo "  generating ${sz}x${sz}..."
      inkscape -z -C -w ${sz} -h ${sz} -e "$dst" "$src" 2>&1 | grep "Bitmap saved as"
    fi
  done
done
