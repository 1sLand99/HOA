/*
 * libhilog_ndk.z.so stub for HOA.
 *
 * OHOS HAP native .so files link against libhilog_ndk.z.so via DT_NEEDED.
 * This stub implements OH_LOG_Print by forwarding to Android's
 * __android_log_print.  OHOS privacy specifiers ({public}/{private})
 * are stripped from the format string since Android's printf does not
 * understand them.
 */
#include <android/log.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

static char *strip_privacy(const char *fmt)
{
    size_t len = strlen(fmt);
    char *out = (char *)malloc(len + 1);
    if (!out) return NULL;
    const char *p = fmt;
    char *q = out;
    while (*p) {
        if (*p == '%' && p[1] == '{') {
            *q++ = *p++;  // copy %
            if (strncmp(p, "{public}", 8) == 0)
                p += 8;
            else if (strncmp(p, "{private}", 9) == 0)
                p += 9;
            else
                *q++ = *p++;  // unknown, copy {
        } else {
            *q++ = *p++;
        }
    }
    *q = '\0';
    return out;
}

int OH_LOG_Print(int type, int level, unsigned int domain, const char *tag,
                 const char *fmt, ...)
{
    (void)type;
    (void)domain;
    char *clean = strip_privacy(fmt);
    va_list ap;
    va_start(ap, fmt);
    int ret = __android_log_vprint(level, tag ? tag : "HOA",
                                   clean ? clean : fmt, ap);
    va_end(ap);
    free(clean);
    return ret;
}

int OH_LOG_IsLoggable(unsigned int domain, const char *tag, int level)
{
    (void)domain;
    (void)tag;
    (void)level;
    return 1;
}

int OH_LOG_PrintMsg(int type, int level, unsigned int domain, const char *tag,
                    const char *message)
{
    (void)type;
    (void)domain;
    return __android_log_write(level, tag ? tag : "HOA", message);
}

int OH_LOG_PrintMsgByLen(int type, int level, unsigned int domain,
                         const char *tag, size_t tagLen,
                         const char *message, size_t messageLen)
{
    (void)type;
    (void)domain;
    (void)tagLen;
    if (messageLen > 0)
        return __android_log_write(level, tag ? tag : "HOA", message);
    return 0;
}

int OH_LOG_VPrint(int type, int level, unsigned int domain, const char *tag,
                  const char *fmt, va_list ap)
{
    (void)type;
    (void)domain;
    char *clean = strip_privacy(fmt);
    int ret = __android_log_vprint(level, tag ? tag : "HOA",
                                   clean ? clean : fmt, ap);
    free(clean);
    return ret;
}

void OH_LOG_SetCallback(void (*callback)(int, int, unsigned int,
                                         const char *, const char *))
{
    (void)callback;
}

void OH_LOG_SetMinLogLevel(int level) { (void)level; }
void OH_LOG_SetLogLevel(int level, int prefer) { (void)level; (void)prefer; }
