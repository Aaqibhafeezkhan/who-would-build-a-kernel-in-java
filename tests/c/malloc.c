#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void) {
    // Small blocks, then a few big enough that malloc maps them on their own.
    size_t sizes[] = {1, 16, 100, 4096, 10000, 200000, 1 << 20, 3 << 20};
    int count = sizeof sizes / sizeof sizes[0];
    unsigned char *blocks[sizeof sizes / sizeof sizes[0]];
    for (int i = 0; i < count; i++) {
        blocks[i] = malloc(sizes[i]);
        if (blocks[i] == NULL) {
            printf("malloc(%zu) failed\n", sizes[i]);
            return 1;
        }
        memset(blocks[i], i + 1, sizes[i]);
    }
    unsigned long sum = 0;
    for (int i = 0; i < count; i++) {
        for (size_t j = 0; j < sizes[i]; j += 97) {
            sum += blocks[i][j];
        }
    }
    printf("sum %lu\n", sum);
    for (int i = 0; i < count; i += 2) {
        free(blocks[i]);
    }

    char *grown = malloc(8);
    strcpy(grown, "growing");
    for (size_t size = 16; size <= 1 << 21; size *= 4) {
        grown = realloc(grown, size);
        if (grown == NULL) {
            printf("realloc(%zu) failed\n", size);
            return 1;
        }
    }
    printf("%s after realloc\n", grown);

    // Many short-lived blocks, so freed memory gets used again.
    for (int round = 0; round < 1000; round++) {
        void *p = malloc(round % 300 + 1);
        free(p);
    }
    int *zeroed = calloc(1000, sizeof(int));
    int nonzero = 0;
    for (int i = 0; i < 1000; i++) {
        nonzero += zeroed[i] != 0;
    }
    printf("calloc nonzero %d\n", nonzero);
    return 0;
}
