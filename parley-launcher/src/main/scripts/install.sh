#!/bin/sh
# Install parley-launcher from this folder:
#
#   PREFIX/bin/parley-launcher   the launcher; each program is a link to it, named for it
#   PREFIX/lib                   parley-launcher.jar
#   PREFIX/apps                  NAME.properties for each program (parley-launcher add)
#   BIN/parley-launcher          a link to it, and BIN/NAME for each program added
#
# PREFIX is /usr/local/lib/parley/launcher (BIN /usr/local/bin), or with --user
# ~/.parley/launcher (BIN ~/.local/bin). Then add the programs, for example:
#
#   parley-launcher add fsh                      (picks the version; or fsh:2.0.0, us.bringardner:fsh:2.0.0)
#
# Usage: ./install.sh [--user] [--prefix DIR] [--bin DIR] [--uninstall]
# Running it again (from a newer one) upgrades: bin and lib are replaced, apps are kept.
# --uninstall removes the launcher, its programs' links and app files; the jars stay in the
# repositories (they are Maven's too).

set -e
unset CDPATH
SRC=$(cd "$(dirname "$0")" && pwd)

user=""
prefix=""
bin=""
uninstall=""
while [ $# -gt 0 ]; do
	case $1 in
	--user) user=yes ;;
	--prefix|--bin)
		[ -n "$2" ] || { echo "$0: $1 needs a folder" >&2; exit 2; }
		case $1 in
		--prefix) prefix=$2 ;;
		--bin) bin=$2 ;;
		esac
		shift ;;
	--uninstall) uninstall=yes ;;
	-h|--help) sed -n '2,/^$/s/^# \{0,1\}//p' "$0"; exit 0 ;;
	*) echo "$0: unknown option $1 (--help lists them)" >&2; exit 2 ;;
	esac
	shift
done

if [ -n "$user" ]; then
	prefix=${prefix:-$HOME/.parley/launcher}
	bin=${bin:-$HOME/.local/bin}
else
	prefix=${prefix:-/usr/local/lib/parley/launcher}
	bin=${bin:-/usr/local/bin}
fi
case $prefix in
/*) ;;
*) prefix=$(pwd)/$prefix ;;
esac
case $bin in
/*) ;;
*) bin=$(pwd)/$bin ;;
esac

# sudo, for the commands that write where this user can't (the nearest folder that exists decides)
writable() {
	d=$1
	while [ ! -e "$d" ]; do
		d=$(dirname "$d")
	done
	[ -w "$d" ]
}
SUDO=""
if [ "$(id -u)" -ne 0 ] && { ! writable "$prefix" || ! writable "$bin"; }; then
	command -v sudo > /dev/null 2>&1 || { echo "$0: $prefix or $bin needs administrator rights, and there is no sudo (or use --user)" >&2; exit 1; }
	SUDO=sudo
	echo "Writing to $prefix and $bin needs administrator rights: sudo may ask for your password."
fi

# a link in BIN that points into PREFIX/bin
ours() {
	[ -L "$1" ] && case $(readlink "$1") in "$prefix"/bin/*) true ;; *) false ;; esac
}

if [ -n "$uninstall" ]; then
	[ -f "$prefix/bin/parley-launcher" ] || { echo "parley-launcher is not installed in $prefix"; exit 0; }
	for l in "$bin"/*; do
		if ours "$l"; then
			$SUDO rm -f "$l"
		fi
	done
	$SUDO rm -rf "$prefix/bin" "$prefix/lib" "$prefix/apps" "$prefix/launcher.conf" "$prefix/LICENSE"
	$SUDO rmdir "$prefix" 2> /dev/null || true
	echo "parley-launcher removed from $prefix"
	exit 0
fi

[ -f "$SRC/lib/parley-launcher.jar" ] && [ -f "$SRC/bin/parley-launcher" ] || { echo "$0: run this from the unpacked parley-launcher folder" >&2; exit 1; }
[ "$SRC" != "$prefix" ] || { echo "$0: this folder is $prefix already" >&2; exit 1; }

$SUDO mkdir -p "$prefix/bin" "$prefix/lib" "$prefix/apps" "$bin"
$SUDO cp "$SRC/bin/parley-launcher" "$prefix/bin/parley-launcher.new"
$SUDO mv -f "$prefix/bin/parley-launcher.new" "$prefix/bin/parley-launcher"
$SUDO cp "$SRC/lib/parley-launcher.jar" "$prefix/lib/parley-launcher.jar.new"
$SUDO mv -f "$prefix/lib/parley-launcher.jar.new" "$prefix/lib/parley-launcher.jar"
[ -f "$SRC/LICENSE" ] && $SUDO cp "$SRC/LICENSE" "$prefix/"
printf 'bin=%s\n' "$bin" | $SUDO tee "$prefix/launcher.conf" > /dev/null
$SUDO chmod 755 "$prefix/bin/parley-launcher"
$SUDO chmod -R a+rX "$prefix/bin" "$prefix/lib" "$prefix/apps" "$prefix/launcher.conf"
$SUDO ln -sf "$prefix/bin/parley-launcher" "$bin/parley-launcher"
# (the programs already added keep their links: an upgrade replaced only what they point at)

echo "parley-launcher installed in $prefix, with $bin/parley-launcher"
echo "add a program with: parley-launcher add NAME [[GROUP:]ARTIFACT[:VERSION]] (parley-launcher --help)"
case :$PATH: in
*:"$bin":*) ;;
*) echo "note: $bin is not on your PATH; add it to run the programs by name" ;;
esac
