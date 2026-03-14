// See LICENSE.Berkeley for license details.

#ifdef _WIN32
#  define WIN32_LEAN_AND_MEAN
#  include <winsock2.h>
#  include <ws2tcpip.h>
#else
#  include <arpa/inet.h>
#  include <fcntl.h>
#  include <unistd.h>
#endif
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>

// Platform-specific socket I/O wrappers
#ifdef _WIN32
// Winsock sockets are not Unix file descriptors; use the Winsock I/O functions.
static inline void socket_set_nonblocking(SOCKET s) {
    u_long mode = 1;
    ioctlsocket(s, FIONBIO, &mode);
}
#  define socket_read(fd, buf, len)  recv((SOCKET)(fd), (buf), (int)(len), 0)
#  define socket_write(fd, buf, len) send((SOCKET)(fd), (buf), (int)(len), 0)
#  define socket_close(fd)           closesocket((SOCKET)(fd))
#  define socket_would_block()       (WSAGetLastError() == WSAEWOULDBLOCK)
#else
static inline void socket_set_nonblocking(int s) {
    fcntl(s, F_SETFL, O_NONBLOCK);
}
#  define socket_read(fd, buf, len)  read((fd), (buf), (len))
#  define socket_write(fd, buf, len) write((fd), (buf), (len))
#  define socket_close(fd)           close(fd)
#  define socket_would_block()       (errno == EAGAIN)
#endif

#include "logging.hpp"
#include "bitbang.hpp"

/////////// remote_bitbang_t

using namespace soct;

remote_bitbang_t::remote_bitbang_t(uint16_t port) :
    socket_fd(0),
    client_fd(0),
    recv_start(0),
    recv_end(0),
    err(0) {
    socket_fd = static_cast<int>(socket(AF_INET, SOCK_STREAM, 0));
    if (socket_fd == -1) {
        logging::fesvr::error << "remote_bitbang failed to make socket: "
            << strerror(errno) << " (" << errno << ")";
        abort();
    }

    socket_set_nonblocking(socket_fd);
    int reuseaddr = 1;
    if (setsockopt(socket_fd, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuseaddr), sizeof(int)) == -1) {
        logging::fesvr::error << "remote_bitbang failed setsockopt: "
            << strerror(errno) << " (" << errno << ")";
        abort();
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);

    if (::bind(socket_fd, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
        logging::fesvr::error << "remote_bitbang failed to bind socket: "
            << strerror(errno) << " (" << errno << ")";
        abort();
    }

    if (listen(socket_fd, 1) == -1) {
        logging::fesvr::error << "remote_bitbang failed to listen on socket: "
            << strerror(errno) << " (" << errno << ")";
        abort();
    }

    socklen_t addrlen = sizeof(addr);
    if (getsockname(socket_fd, (struct sockaddr*)&addr, &addrlen) == -1) {
        logging::fesvr::error << "remote_bitbang getsockname failed: "
            << strerror(errno) << " (" << errno << ")";
        abort();
    }

    tck = 1;
    tms = 1;
    tdi = 1;
    trstn = 1;
    quit = 0;

    logging::fesvr::info << "This emulator compiled with JTAG Remote Bitbang client" << "\n";
    logging::fesvr::info << "Listening on port " << ntohs(addr.sin_port) << "\n";
}

void remote_bitbang_t::accept() {
    logging::fesvr::info << "Attempting to accept client socket";
    int again = 1;
    while (again != 0) {
        client_fd = static_cast<int>(::accept(socket_fd, nullptr, nullptr));
        if (client_fd == -1) {
            if (socket_would_block()) {
            } else {
                logging::fesvr::error << "failed to accept on socket: "
                    << strerror(errno) << " (" << errno << ")" << "\n";
                again = 0;
                abort();
            }
        } else {
            socket_set_nonblocking(client_fd);
            logging::fesvr::info << "Accepted successfully." << "\n";
            again = 0;
        }
    }
}

void remote_bitbang_t::tick(
    unsigned char* jtag_tck,
    unsigned char* jtag_tms,
    unsigned char* jtag_tdi,
    unsigned char* jtag_trstn,
    unsigned char jtag_tdo
) {
    if (client_fd > 0) {
        tdo = jtag_tdo;
        execute_command();
    } else {
        this->accept();
    }

    *jtag_tck = tck;
    *jtag_tms = tms;
    *jtag_tdi = tdi;
    *jtag_trstn = trstn;
}

void remote_bitbang_t::reset() {
    //trstn = 0;
}

void remote_bitbang_t::set_pins(char _tck, char _tms, char _tdi) {
    tck = _tck;
    tms = _tms;
    tdi = _tdi;
}

void remote_bitbang_t::execute_command() {
    char command;
    int again = 1;
    while (again) {
        int num_read = socket_read(client_fd, &command, sizeof(command));
        if (num_read == -1) {
            if (socket_would_block()) {
                logging::fesvr::debug << "Received no command. Will try again on the next call";
            } else {
                logging::fesvr::error << "remote_bitbang failed to read on socket: "
                    << strerror(errno) << " (" << errno << ")" << "\n";
                again = 0;
                abort();
            }
        } else if (num_read == 0) {
            logging::fesvr::info << "No Command Received." << "\n";
            again = 1;
        } else {
            again = 0;
        }
    }

    logging::fesvr::debug << "Received a command " << command;

    int dosend = 0;
    char tosend = '?';

    switch (command) {
    case 'B': /* logging::fesvr::info << "*BLINK*"; */ break;
    case 'b': /* logging::fesvr::info << "_______"; */ break;
    case 'r': reset();
        break; // This is wrong. 'r' has other bits that indicated TRST and SRST.
    case '0': set_pins(0, 0, 0);
        break;
    case '1': set_pins(0, 0, 1);
        break;
    case '2': set_pins(0, 1, 0);
        break;
    case '3': set_pins(0, 1, 1);
        break;
    case '4': set_pins(1, 0, 0);
        break;
    case '5': set_pins(1, 0, 1);
        break;
    case '6': set_pins(1, 1, 0);
        break;
    case '7': set_pins(1, 1, 1);
        break;
    case 'R': dosend = 1;
        tosend = tdo ? '1' : '0';
        break;
    case 'Q': quit = 1;
        break;
    default:
        logging::fesvr::error << "remote_bitbang got unsupported command '" << command << "'" << "\n";
    }

    if (dosend) {
        while (1) {
            int bytes = socket_write(client_fd, &tosend, sizeof(tosend));
            if (bytes == -1) {
                logging::fesvr::error << "failed to write to socket: "
                    << strerror(errno) << " (" << errno << ")" << "\n";
                abort();
            }
            if (bytes > 0) {
                break;
            }
        }
    }

    if (quit) {
        // The remote disconnected.
        logging::fesvr::info << "Remote end disconnected" << "\n";
        socket_close(client_fd);
        client_fd = 0;
    }
}