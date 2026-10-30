// Mouse + keyboard virtual (uinput). Membaca perintah dari stdin:
//  mm <dx> <dy> | bd | bu | rd | ru | kd <code> | ku <code> | q
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static void ev(int fd, int t, int c, int v) {
    struct input_event e; memset(&e, 0, sizeof e);
    e.type = t; e.code = c; e.value = v; write(fd, &e, sizeof e);
}
static void syn(int fd) { ev(fd, EV_SYN, SYN_REPORT, 0); }
static int mk(const char *name, int *keys, int nk, int *rels, int nr) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { perror("open uinput"); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int i = 0; i < nk; i++) ioctl(fd, UI_SET_KEYBIT, keys[i]);
    if (nr) { ioctl(fd, UI_SET_EVBIT, EV_REL); for (int i = 0; i < nr; i++) ioctl(fd, UI_SET_RELBIT, rels[i]); }
    struct uinput_setup s; memset(&s, 0, sizeof s);
    s.id.bustype = BUS_USB; s.id.vendor = 0x1234; s.id.product = 0x5678;
    strncpy(s.name, name, UINPUT_MAX_NAME_SIZE - 1);
    ioctl(fd, UI_DEV_SETUP, &s); ioctl(fd, UI_DEV_CREATE);
    return fd;
}
int main(void) {
    int mk_keys[] = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE}; int rels[] = {REL_X, REL_Y, REL_WHEEL};
    int kb_keys[] = {KEY_A, KEY_W, KEY_S, KEY_D, KEY_SPACE, KEY_R};
    int mouse = mk("AA Test Mouse", mk_keys, 3, rels, 3);
    int kb = mk("AA Test Keyboard", kb_keys, 6, 0, 0);
    if (mouse < 0 || kb < 0) return 1;
    sleep(2); puts("CREATED"); fflush(stdout);
    char line[128];
    while (fgets(line, sizeof line, stdin)) {
        int a = 0, b = 0;
        if (!strncmp(line, "mm", 2)) { sscanf(line + 2, "%d %d", &a, &b); ev(mouse, EV_REL, REL_X, a); ev(mouse, EV_REL, REL_Y, b); syn(mouse); }
        else if (!strncmp(line, "bd", 2)) { ev(mouse, EV_KEY, BTN_LEFT, 1); syn(mouse); }
        else if (!strncmp(line, "bu", 2)) { ev(mouse, EV_KEY, BTN_LEFT, 0); syn(mouse); }
        else if (!strncmp(line, "kd", 2)) { sscanf(line + 2, "%d", &a); ev(kb, EV_KEY, a, 1); syn(kb); }
        else if (!strncmp(line, "ku", 2)) { sscanf(line + 2, "%d", &a); ev(kb, EV_KEY, a, 0); syn(kb); }
        else if (line[0] == 'q') break;
        fflush(stdout);
    }
    return 0;
}
