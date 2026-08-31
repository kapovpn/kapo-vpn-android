#ifndef ARGHANDLING_H
#define ARGHANDLING_H

#include <stdbool.h>

/* Taken from emacs */
#define ARRAYELTS(arr) (sizeof (arr) / sizeof (arr)[0])

#ifdef __cplusplus
extern "C" {
#endif

bool
argmatch (char **argv, int argc, const char *sstr, const char *lstr,
          int minlen, int *valptr, int *skipptr);

#ifdef __cplusplus
}
#endif

#endif
