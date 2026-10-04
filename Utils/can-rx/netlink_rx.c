// Passive diagnostic for the H97X cis_can -> Android netlink transport.
// No send/sendto/ioctl calls and no CAN device opens. See Docs/oem-cluster-telemetry.md.
#include <errno.h>
#include <linux/netlink.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static long long millis(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

int main(int argc, char **argv) {
    long seconds = 30;
    if (argc > 2) return 2;
    if (argc == 2) {
        char *end;
        errno = 0;
        seconds = strtol(argv[1], &end, 10);
        if (errno || end == argv[1] || *end || seconds < 1 || seconds > 55) {
            fprintf(stderr, "Duration must be an integer between 1 and 55 seconds\n");
            return 2;
        }
    }
    int fd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, 21);
    if (fd < 0) { perror("socket"); return 1; }
    struct sockaddr_nl local = {.nl_family = AF_NETLINK, .nl_pid = 0, .nl_groups = 1};
    int rcvbuf = 512 * 1024;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf)) < 0) {
        perror("SO_RCVBUF"); close(fd); return 1;
    }
    if (bind(fd, (struct sockaddr *)&local, sizeof(local)) < 0) {
        perror("bind"); close(fd); return 1;
    }
    unsigned char buf[8192];
    unsigned int count = 0, dropped = 0;
    int failed = 0;
    long long deadline = millis() + seconds * 1000;
    fprintf(stderr, "RX only: netlink21/group1, %lds\n", seconds);
    while (millis() < deadline && count < 100000) {
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int timeout = (int)(deadline - millis());
        if (timeout <= 0) break;
        int ready = poll(&p, 1, timeout < 1000 ? timeout : 1000);
        if (ready < 0) {
            if (errno == EINTR) continue;
            perror("poll"); failed = 1; break;
        }
        if (!ready) continue;
        if (p.revents & (POLLNVAL | POLLHUP)) { failed = 1; break; }
        struct sockaddr_nl sender = {0};
        socklen_t len = sizeof(sender);
        ssize_t n = recvfrom(fd, buf, sizeof(buf), MSG_DONTWAIT | MSG_TRUNC,
                (struct sockaddr *)&sender, &len);
        if (n < 0) {
            if (errno == ENOBUFS) { dropped++; continue; }
            if (errno == EAGAIN || errno == EINTR) continue;
            perror("recvfrom"); failed = 1; break;
        }
        if (n > (ssize_t)sizeof(buf)) { dropped++; continue; }
        if (len < sizeof(sender) || sender.nl_family != AF_NETLINK || sender.nl_pid != 0) continue;
        printf("%lld %zd ", millis(), n);
        for (ssize_t i = 0; i < n; i++) printf("%02x", buf[i]);
        putchar('\n');
        if (ferror(stdout)) { failed = 1; break; }
        count++;
    }
    close(fd);
    if (fflush(stdout) == EOF) failed = 1;
    fprintf(stderr, "RX complete: packets=%u, overflow_or_truncated=%u\n", count, dropped);
    return failed || dropped || count == 100000 ? 1 : 0;
}
