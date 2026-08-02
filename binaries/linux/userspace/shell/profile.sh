# /etc/profile of the shell boot image - runs once per login shell. A serial console
# reports a 0x0 window size, which makes busybox line editing probe the terminal with an
# ESC[6n cursor-position query at EVERY prompt - and the reply, arriving between prompts,
# leaks into the input line as "[40;5R" garbage. Pinning ANY size stops the probing
# (Debian's BusyBox ships no `resize` applet to measure the real terminal); 120x40 fits
# the common case, and only full-screen applets like vi care about exactness.
stty rows 40 cols 120

# The prompt names the machine AND the persistence story: this shell lives on the
# initramfs, so it introduces itself as ramdisk@<board> - inside the persistent
# environment (/bin/soct) the prompt becomes soct@<board>. The hostname comes from
# the design's device tree (/chosen/soct,board).
export PS1="ramdisk@$(hostname):\w # "
