#pragma once

#include <stddef.h>

/// Write a character to the UART
void kputc(char ch);

/// Write a string to the UART. If len is 0, the string is null-terminated and it stops at the first null character.
void kputs(const char * s, size_t len);

/// Read a character from the UART
char kgetc();