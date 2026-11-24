#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#define BIG 200000

static unsigned char pattern(long i, int seed) {
    return (unsigned char) (i + (i >> 8) * 7 + seed * 101);
}

// Reads until the peer shuts down its side, checking every byte against pattern(seed).
static long drain(int fd, int seed, int *wrong) {
    unsigned char buffer[3000];
    long total = 0;
    ssize_t n;
    *wrong = 0;
    while ((n = read(fd, buffer, sizeof buffer)) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            *wrong |= buffer[i] != pattern(total + i, seed);
        }
        total += n;
    }
    return n < 0 ? -errno : total;
}

static int fill(int fd, int seed) {
    unsigned char buffer[4000];
    for (long sent = 0; sent < BIG; ) {
        int n = BIG - sent < (long) sizeof buffer ? (int) (BIG - sent) : (int) sizeof buffer;
        for (int i = 0; i < n; i++) {
            buffer[i] = pattern(sent + i, seed);
        }
        ssize_t written = write(fd, buffer, n);
        if (written <= 0) {
            return -1;
        }
        sent += written;
    }
    return shutdown(fd, SHUT_WR);
}

static const char *outcome(int result) {
    return result == 0 ? "ok" : strerror(errno);
}

// Listens on 127.0.0.1 and forks a child that connects back: a line each way, then BIG bytes each way.
int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    int one = 1;
    printf("SO_REUSEADDR: %s\n", outcome(setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one)));
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    printf("bind: %s\n", outcome(bind(listener, (struct sockaddr *) &address, sizeof address)));
    printf("listen: %s\n", outcome(listen(listener, 4)));
    socklen_t length = sizeof address;
    getsockname(listener, (struct sockaddr *) &address, &length);
    printf("listening on %s, %s port\n", inet_ntoa(address.sin_addr), address.sin_port != 0 ? "a free" : "no");

    pid_t child = fork();
    if (child == 0) {
        int s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (connect(s, (struct sockaddr *) &address, sizeof address) != 0) {
            printf("child connect: %s\n", strerror(errno));
            _exit(1);
        }
        printf("TCP_NODELAY: %s\n", outcome(setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one)));
        struct sockaddr_in peer;
        socklen_t peerLength = sizeof peer;
        getpeername(s, (struct sockaddr *) &peer, &peerLength);
        printf("child connected to %s, %s port\n", inet_ntoa(peer.sin_addr),
                peer.sin_port == address.sin_port ? "the listener's" : "some other");
        const char *hello = "hello from the child\n";
        send(s, hello, strlen(hello), MSG_NOSIGNAL);
        char line[100];
        ssize_t n = recv(s, line, sizeof line - 1, 0);
        line[n > 0 ? n : 0] = 0;
        printf("child got: %s", line);
        int wrong;
        if (fill(s, 1) != 0) {
            printf("child fill: %s\n", strerror(errno));
        }
        long got = drain(s, 2, &wrong);
        printf("child read %ld bytes, %s\n", got, wrong ? "some wrong" : "all right");
        close(s);
        _exit(0);
    }

    struct sockaddr_in peer;
    socklen_t peerLength = sizeof peer;
    int c = accept4(listener, (struct sockaddr *) &peer, &peerLength, SOCK_CLOEXEC);
    char line[100];
    ssize_t n = read(c, line, sizeof line - 1);
    line[n > 0 ? n : 0] = 0;
    // Only now, once the child has said its piece, so the two don't print at once.
    printf("accepted from %s, address length %u\n", inet_ntoa(peer.sin_addr), peerLength);
    printf("parent got: %s", line);
    const char *reply = "hello from the parent\n";
    write(c, reply, strlen(reply));
    int wrong;
    long got = drain(c, 1, &wrong);
    printf("parent read %ld bytes, %s\n", got, wrong ? "some wrong" : "all right");
    if (fill(c, 2) != 0) {
        printf("parent fill: %s\n", strerror(errno));
    }
    close(c);
    int status;
    waitpid(child, &status, 0);
    printf("child exited with %d\n", WEXITSTATUS(status));

    int again = socket(AF_INET, SOCK_STREAM, 0);
    int taken = bind(again, (struct sockaddr *) &address, sizeof address);
    printf("a second listener on that port: %s\n", outcome(taken == 0 ? listen(again, 4) : taken));
    close(again);
    printf("getpeername on the listener: %s\n", outcome(getpeername(listener, (struct sockaddr *) &peer, &peerLength)));
    close(listener);

    int refused = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in closed = {.sin_family = AF_INET, .sin_port = htons(1), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    printf("connect to a closed port: %s\n", outcome(connect(refused, (struct sockaddr *) &closed, sizeof closed)));
    printf("read before connecting: %s\n", read(socket(AF_INET, SOCK_STREAM, 0), line, 1) < 0 ? strerror(errno) : "ok");
    printf("recv on a descriptor that isn't open: %s\n", recv(100, line, 1, 0) < 0 ? strerror(errno) : "ok");
    int fds[2];
    pipe(fds);
    printf("shutdown on a pipe: %s\n", outcome(shutdown(fds[0], SHUT_RD)));
    return 0;
}
