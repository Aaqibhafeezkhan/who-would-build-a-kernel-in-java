#include <stdio.h>

// Reads its own executable, the one file it can count on finding on Linux and on Duke.
int main(int argc, char **argv) {
    FILE *f = fopen(argv[0], "rb");
    if (f == NULL) {
        perror("fopen");
        return 1;
    }
    unsigned char magic[4];
    if (fread(magic, 1, 4, f) != 4) {
        puts("short read");
        return 1;
    }
    printf("%02x %c%c%c\n", magic[0], magic[1], magic[2], magic[3]);
    long total = 4;
    char buffer[1000];
    size_t n;
    while ((n = fread(buffer, 1, sizeof buffer, f)) > 0) {
        total += n;
    }
    fseek(f, 0, SEEK_END);
    printf("read %s\n", total == ftell(f) ? "all of it" : "a different length");
    fclose(f);
    return 0;
}
