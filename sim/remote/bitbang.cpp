// See LICENSE.Berkeley for license details.

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>

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
    socket_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (socket_fd == -1) {
        logging::fesvr::error << "remote_bitbang failed to make socket: "
            << strerror(errno) << " (" << errno << ")";
        abort();
    }

    fcntl(socket_fd, F_SETFL, O_NONBLOCK);
    int reuseaddr = 1;
    if (setsockopt(socket_fd, SOL_SOCKET, SO_REUSEADDR, &reuseaddr, sizeof(int)) == -1) {
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
        client_fd = ::accept(socket_fd, NULL, NULL);
        if (client_fd == -1) {
            if (errno == EAGAIN) {
                // No client waiting to connect right now.
            } else {
                logging::fesvr::error << "failed to accept on socket: "
                    << strerror(errno) << " (" << errno << ")" << "\n";
                again = 0;
                abort();
            }
        } else {
            fcntl(client_fd, F_SETFL, O_NONBLOCK);
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
        ssize_t num_read = read(client_fd, &command, sizeof(command));
        if (num_read == -1) {
            if (errno == EAGAIN) {
                // We'll try again the next call.
                // logging::fesvr::info << "Received no command. Will try again on the next call";
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

    // logging::fesvr::info << "Received a command " << command;

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
            ssize_t bytes = write(client_fd, &tosend, sizeof(tosend));
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
        close(client_fd);
        client_fd = 0;
    }
}
