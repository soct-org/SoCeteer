/*
 * PID 1 of the bring-up initramfs, as an ordinary Linux userspace program (static
 * riscv64-linux-musl via programs/linux-userspace.cmake). Mounts the pseudo
 * filesystems, reports what came up, then echoes console input back forever -
 * verifying the console in both directions. PID 1 must never exit.
 */
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/utsname.h>

int main(void) {
    /* The console may be freshly attached; keep output ordered and immediate. */
    setvbuf(stdout, NULL, _IONBF, 0);

    mkdir("/proc", 0755);
    mkdir("/sys", 0755);
    mkdir("/dev", 0755);
    if (mount("proc", "/proc", "proc", 0, "") != 0) perror("mount /proc");
    if (mount("sysfs", "/sys", "sysfs", 0, "") != 0) perror("mount /sys");
    if (mount("devtmpfs", "/dev", "devtmpfs", 0, "") != 0) perror("mount /dev");

    printf("\n=== SoCeteer Linux userspace is alive ===\n");
    struct utsname u;
    if (uname(&u) == 0) printf("%s %s (%s)\n", u.sysname, u.release, u.machine);
    printf("CPUs online: %ld\n", sysconf(_SC_NPROCESSORS_ONLN));

    printf("Type something; it will be echoed back.\necho> ");
    char line[256];
    for (;;) {
        if (fgets(line, sizeof line, stdin)) {
            printf("you said: %s", line);
            printf("echo> ");
        } else {
            clearerr(stdin);
            usleep(100 * 1000);
        }
    }
}
