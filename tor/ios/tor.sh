#!/bin/bash
set -e

[[ -z "$ARCH" ]] && echo "Please set the ARCH variable" && exit 1

# Compiler options
OPT_FLAGS="-O3 -g3 -fembed-bitcode"
MAKE_JOBS=8
MIN_IOS_VERSION=12.0

if [ "$ARCH" == "arm64" ]; then
  SDK="iphoneos"
  HOST_FLAGS="-arch arm64 -arch arm64e -miphoneos-version-min=${MIN_IOS_VERSION} -isysroot $(xcrun --sdk ${SDK} --show-sdk-path)"
  CHOST="arm-apple-darwin"
elif [ "$ARCH" == "x86_64" ]; then
  SDK="iphonesimulator"
  HOST_FLAGS="-arch x86_64 -mios-simulator-version-min=${MIN_IOS_VERSION} -isysroot $(xcrun --sdk ${SDK} --show-sdk-path)"
  CHOST="x86_64-apple-darwin"
else
  echo "Unsupported ARCH: $ARCH"
  exit 1
fi

# Locations
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
PREFIX="${SCRIPT_DIR}/build/${ARCH}"

cd ../libs/tor

# We need gettext
# This extends the path to look in some common locations (for example, if installed via Homebrew)
PATH=$PATH:/usr/local/bin:/usr/local/opt/gettext/bin

if [[ ! -f ./configure ]]; then
  export LIBTOOLIZE=glibtoolize
  ./autogen.sh
fi

# Ensure -fembed-bitcode builds, as workaround for libtool macOS bug
export MACOSX_DEPLOYMENT_TARGET="10.4"
# Get the correct toolchain for target platforms
CC=$(xcrun --find --sdk "${SDK}" clang)
export CC
AR=$(xcrun --find --sdk "${SDK}" ar)
export AR
RANLIB=$(xcrun --find --sdk "${SDK}" ranlib)
export RANLIB
export CFLAGS="${HOST_FLAGS} ${OPT_FLAGS} -I${PREFIX}/include"
export LDFLAGS="${HOST_FLAGS}"

./configure \
  --host="${CHOST}" \
  --prefix="${PREFIX}" \
  --enable-static --disable-shared \
  --enable-restart-debugging --enable-silent-rules --enable-pic --disable-module-dirauth --disable-tool-name-check --disable-unittests --enable-static-openssl \
  --enable-static-libevent --disable-asciidoc --disable-system-torrc --disable-linker-hardening --disable-dependency-tracking --disable-manpage --disable-html-manual \
  --enable-lzma --enable-zstd=no \
  --with-libevent-dir="${PREFIX}" --with-openssl-dir="${PREFIX}"\
  cross_compiling="yes" ac_cv_func_clock_gettime="no"

make clean
mkdir -p "${PREFIX}" &> /dev/null
make -j"${MAKE_JOBS}"
make install

mkdir -p "${PREFIX}/lib" &> /dev/null
for LIB in $(make show-libs) ; do
  cp "$LIB" "${PREFIX}/lib/$(basename $LIB)"
done

cp src/feature/api/tor_api.h "${PREFIX}/include"
