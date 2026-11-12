#include <stdio.h>

int main(int argc, char **argv) {
    puts("hello from C");
    for (int i = 1; i < argc; i++) {
        puts(argv[i]);
    }
    return 0;
}
