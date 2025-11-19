// Specify the posix version we want to support
#define _POSIX_C_SOURCE 200809L

#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <string_view>
#include <string>

#ifdef TEST_CXX_IO
#include <iostream>
#endif

using std::string_view_literals::operator""sv;
using std::string_literals::operator ""s;

// override flags to match host system. You must verify these flags are correct for your system.
#undef O_CREAT
#undef O_DIRECTORY
#undef O_RDWR

#define O_CREAT 0100
#define O_DIRECTORY 0200000
#define O_RDWR 02

void ser_errno(int err) {
    printf(strerror(err));
}

int main(int argc, char** argv) {
    /******************************************************************************************************/
    printf("[Testing if I can receive the args passed via the FESVR]\n");
    printf("I received %d arguments\n", argc);
    for (int i = 0; i < argc; i++) {
        printf("Argument %d: %s\n", i, argv[i]);
    }
    if (argc < 2) {
        printf("Why don't you pass me the path to a directory which I can use to test the syscalls?\n");
        return 0;
    }
    /******************************************************************************************************/
    printf("[Testing open syscall]\n");
    // Open a file descriptor
    const char* dir = argv[1];
    const int dir_fd = open(dir, O_DIRECTORY, 0);
    if (dir_fd < 0) {
        printf("Failed to open directory \"%s\" with error: %s\n", dir, strerror(-dir_fd));
        return 1;
    }
    printf("Successfully opened directory \"%s\" with file descriptor %d\n", dir, dir_fd);
    /******************************************************************************************************/
    printf("[Testing openat syscall]\n");
    // use openat to open a file in the directory
    constexpr auto filename = "syscall-test.txt"sv;
    const int file_fd = openat(dir_fd, filename.data(), O_RDWR | O_CREAT, 0644);
    if (file_fd < 0) {
        printf("Failed to open file \"%s\" with error: %s\n", filename.data(), strerror(-file_fd));
        return 1;
    }
    printf("Successfully opened file \"%s\" with file descriptor %d\n", filename.data(), file_fd);
    /******************************************************************************************************/
    printf("[Testing write syscall]\n");
    // write to the file
    constexpr auto msg_write = "Hello, world!\n"sv;
    write(file_fd, msg_write.data(), msg_write.size());
    printf("Successfully wrote to file\n");
    /******************************************************************************************************/
    printf("[Testing lseek syscall]\n");
    // seek to the beginning of the file
    if (const off_t ret = lseek(file_fd, 0, SEEK_SET); ret < 0) {
        printf("Failed to seek to the beginning of the file with error: %s\n", strerror(-ret));
        return 1;
    }
    printf("Successfully seeked to the beginning of the file \"%s\"\n", filename.data());
    /******************************************************************************************************/
    printf("[Testing read syscall]\n");
    std::array<char, msg_write.size() + 1> buf{}; // +1 for null terminator
    read(file_fd, buf.data(), buf.size());
    if (msg_write != buf.data()) {
        printf("Expected to read \"%s\", but read \"%s\"\n", msg_write.data(), buf.data());
        return 1;
    }
    printf("Successfully read back from file \"%s\"\n", filename.data());
    /******************************************************************************************************/
    printf("[Testing close syscall]\n");
    if (const int ret = close(file_fd); ret < 0) {
        printf("Failed to close directory \"%s\" with error %s\n", filename.data(), strerror(-ret));
        return 1;
    }
    printf("Successfully closed file descriptor %d\n", file_fd);
    /******************************************************************************************************/
    printf("[Testing fetching from stdin]\n");
    printf("Please enter a string: \n");
    std::array<char, 256> input{};
    fgets(input.data(), input.size(), stdin);
    printf("You entered: %s", input.data());
    /******************************************************************************************************/
    printf("[Testing fopen]\n");
    const std::string full_path = dir + "/"s + filename.data();
    FILE* file = fopen(full_path.c_str(), "r");
    if (file == nullptr) {
        printf("Failed to open file \"%s\" with error: %s\n", full_path.c_str(), strerror(errno));
        return 1;
    }
    printf("Successfully opened file \"%s\" with FILE*\n", full_path.c_str());
    /******************************************************************************************************/
    printf("[Testing fread]\n");
    std::array<char, msg_write.size() + 1> buf2{}; // +1 for null terminator
    fread(buf2.data(), 1, msg_write.size(), file);
    if (msg_write != buf2.data()) {
        printf("Expected to read \"%s\", but read \"%s\"\n", msg_write.data(), buf2.data());
        return 1;
    }
    printf("Successfully read back from file \"%s\"\n", filename.data());
    // Seek to the beginning of the file
    fseek(file, 0, SEEK_SET);
    /******************************************************************************************************/
#ifdef TEST_CXX_IO
    printf("[Testing C++ io functions]\n");
    std::cout << "I even support std::cout!\n";
    std::cout << "Please enter a string again:\n";
    std::string input2;
    std::getline(std::cin, input2);
    std::cout << "You entered: " << input2 << "\n";
#endif
    /******************************************************************************************************/


    printf("I even support floating point numbers: %f\n", 3.14159);

    return 0;
}
