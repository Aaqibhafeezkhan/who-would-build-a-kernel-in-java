#include <limits.h>
#include <stdint.h>
#include <stdio.h>

int main(void) {
    printf("%d %d %d\n", 0, -42, INT_MIN);
    printf("%ld %lu\n", LONG_MIN, ULONG_MAX);
    printf("%x %X %o %#x\n", 255u, 0xBEEFu, 8u, 16u);
    printf("[%5d] [%-5d] [%05d]\n", 42, 42, 42);
    printf("%s %c %.3s %%\n", "string", 'c', "truncated");
    printf("%f %.2f %e %g\n", 3.14159, -2.5, 12345.678, 0.0001);
    printf("%zu %p\n", sizeof(int64_t), (void *) 0);
    // A nonzero exit, to check it comes through.
    return 7;
}
