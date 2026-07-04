// popen/pclose bridge: replacement for musl popen/pclose in HOA.
//
// WHY not use musl's popen/pclose directly?
//   musl's popen calls posix_spawn which uses vfork/clone.  When called
//   from a musl thread (TPIDR_EL0 = musl pthread), the child inherits
//   musl TLS.  bionic's post-fork handlers access bionic TLS slots via
//   TPIDR_EL0, reading garbage from musl's layout.  We use raw fork()
//   + execve() instead.
//
// WHY use __fdopenx instead of fdopen()?
//   fdopen() routes through our malloc_bridge wrapper → bionic_fdopen →
//   returns a bionic FILE*.  Bionic FILE* has a different struct layout
//   from musl FILE*.  Calling musl's fgets/fclose/fileno on it reads
//   wrong offsets → SIGSEGV.  __fdopenx creates a musl FILE* directly.

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/wait.h>
#include <pthread.h>
#include <string.h>

// Use musl's internal __fdopenx to create a musl FILE* directly, instead
// of fdopen() which routes through our bridge → bionic → bionic FILE*.
// The bionic FILE* has a different struct layout, causing musl's fgets/
// fclose/fileno to crash with SIGSEGV on random struct offsets.
//
// __fdopenx is defined in musl/src/stdio/__fdopen.c and compiled into
// libb.so.  -Wl,-Bsymbolic ensures internal calls resolve to libb.so's
// own copy, not bionic's.
extern FILE *__fdopenx(int fd, int flags);

// flags values for __fdopenx:
#define F_NORD  4
#define F_NOWR  8

// Avoid pulling in musl internal headers; manage pid/fd tracking
// ourselves.  popen returns a bionic FILE* (via fdopen wrapper), but
// pclose must NOT call musl's fileno/fclose on it — the struct layouts
// are incompatible.  Instead we track the fd independently.
//
// Layout:
//   popen_files[fd] = FILE*     (validate the mapping in pclose)
//   popen_pids[fd]  = pid       (reap child in pclose)

#define MAX_POPEN_FDS 32
static FILE *popen_files[MAX_POPEN_FDS];
static pid_t popen_pids[MAX_POPEN_FDS];

// ── popen ─────────────────────────────────────────────────────────────
// Spawns a shell command, returning a FILE* connected to its stdin or stdout.
//
// For mode "r": caller reads from the FILE*  (child's stdout is the pipe)
// For mode "w": caller writes to the FILE*    (child's stdin is the pipe)

FILE *popen(const char *cmd, const char *mode)
{
    int p[2], op;
    pid_t pid;

    if (*mode == 'r')
        op = 0;          // parent reads  p[0], child writes p[1]
    else if (*mode == 'w')
        op = 1;          // parent writes p[1], child reads  p[0]
    else {
        errno = EINVAL;
        return NULL;
    }

    if (pipe2(p, O_CLOEXEC) != 0)
        return NULL;

    pid = fork();
    if (pid < 0) {
        close(p[0]);
        close(p[1]);
        return NULL;
    }

    if (pid == 0) {
        // ── child ──────────────────────────────────────────────────
        // Close the pipe end the child does NOT use, then dup the other
        // end to stdout (read mode) or stdin (write mode).
        if (op == 0) {
            close(p[0]);                       // close read end
            dup2(p[1], STDOUT_FILENO);        // redirect stdout → pipe
        } else {
            close(p[1]);                       // close write end
            dup2(p[0], STDIN_FILENO);         // redirect stdin → pipe
        }
        close(p[1 - op]);                     // close the dup'd fd

        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }

    // ── parent ──────────────────────────────────────────────────────
    close(p[1 - op]);   // close the end the parent does NOT use

    // Use musl's __fdopenx directly — fdopen() would return a bionic FILE*
    int fflags = (op == 0) ? F_NOWR : F_NORD;
    FILE *f = __fdopenx(p[op], fflags);
    if (!f) {
        close(p[op]);
        // Reap the child so it doesn't become a zombie
        int status;
        waitpid(pid, &status, 0);
        return NULL;
    }

    // Record pid + FILE* for pclose
    int fd = p[op];
    if (fd >= 0 && fd < MAX_POPEN_FDS) {
        popen_files[fd] = f;
        popen_pids[fd] = pid;
    }

    return f;
}

// ── pclose ────────────────────────────────────────────────────────────
// Closes a pipe opened by popen and waits for the child process.

int pclose(FILE *f)
{
    int fd = fileno(f);
    pid_t pid = -1;

    if (fd >= 0 && fd < MAX_POPEN_FDS) {
        pid = popen_pids[fd];
        popen_files[fd] = NULL;
        popen_pids[fd] = 0;
    }

    fclose(f);

    if (pid <= 0)
        return -1;

    int status;
    while (waitpid(pid, &status, 0) == -1) {
        if (errno != EINTR)
            return -1;
    }
    return status;
}
