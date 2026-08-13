#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define FILE_PATH "data.bin"

// Function to load an integer at a given offset in the file
int load_integer_at_offset(void *mapped_file, size_t offset) {
    // Cast the mapped file to an int pointer
    int *int_array = (int *)mapped_file;

    // Access the integer at the specified offset
    return int_array[offset];
}

int main() {
    int fd;
    struct stat file_stat;
    void *mapped_file;

    // Open the file for reading and writing
    fd = open(FILE_PATH, O_RDWR);
    if (fd == -1) {
        perror("Failed to open file");
        exit(EXIT_FAILURE);
    }

    // Get the file size
    if (fstat(fd, &file_stat) == -1) {
        perror("Failed to get file stats");
        close(fd);
        exit(EXIT_FAILURE);
    }

    // Memory-map the file
    mapped_file = mmap(NULL, file_stat.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapped_file == MAP_FAILED) {
        perror("Failed to map file");
        close(fd);
        exit(EXIT_FAILURE);
    }

    // Close the file descriptor as it is no longer needed
    close(fd);

    // Example usage: load integers from the file
    size_t offset = 2; // Example offset
    int value = load_integer_at_offset(mapped_file, offset);
    printf("Integer at offset %zu: %d\n", offset, value);

    // Clean up
    if (munmap(mapped_file, file_stat.st_size) == -1) {
        perror("Failed to unmap file");
        exit(EXIT_FAILURE);
    }

    return 0;
}