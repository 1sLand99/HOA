/*
 * libohaudio.so stub for HOA.
 *
 * OHOS HAP native .so files link against libohaudio.so by DT_NEEDED for
 * OHAudio C API (OH_AudioStreamBuilder_*, OH_AudioRenderer_*).
 * This stub makes dlopen succeed and returns errors from GenerateRenderer
 * so callers fall back to silent/no-audio mode.
 *
 * SONAME is "libohaudio.so" to match the OHOS name.
 */

#include <stdint.h>

/* ---- typedefs from native_audiostream_base.h ---- */
enum {
    AUDIOSTREAM_SUCCESS            = 0,
    AUDIOSTREAM_ERROR_INVALID_PARAM = 1,
    AUDIOSTREAM_ERROR_ILLEGAL_STATE = 2,
    AUDIOSTREAM_ERROR_SYSTEM       = 3,
};

typedef enum {
    AUDIOSTREAM_TYPE_RENDERER = 1,
    AUDIOSTREAM_TYPE_CAPTURER = 2,
} OH_AudioStream_Type;

typedef enum {
    AUDIOSTREAM_SAMPLE_U8   = 0,
    AUDIOSTREAM_SAMPLE_S16LE = 1,
    AUDIOSTREAM_SAMPLE_S24LE = 2,
    AUDIOSTREAM_SAMPLE_S32LE = 3,
    AUDIOSTREAM_SAMPLE_F32LE = 4,
} OH_AudioStream_SampleFormat;

typedef enum {
    AUDIOSTREAM_ENCODING_TYPE_RAW = 0,
} OH_AudioStream_EncodingType;

typedef enum {
    AUDIOSTREAM_LATENCY_MODE_NORMAL = 0,
    AUDIOSTREAM_LATENCY_MODE_FAST   = 1,
} OH_AudioStream_LatencyMode;

typedef enum {
    AUDIOSTREAM_USAGE_UNKNOWN            = 0,
    AUDIOSTREAM_USAGE_MUSIC              = 1,
    AUDIOSTREAM_USAGE_VOICE_COMMUNICATION = 2,
    AUDIOSTREAM_USAGE_VOICE_ASSISTANT    = 3,
    AUDIOSTREAM_USAGE_ALARM              = 4,
    AUDIOSTREAM_USAGE_VOICE_MESSAGE      = 5,
    AUDIOSTREAM_USAGE_MOVIE              = 10,
    AUDIOSTREAM_USAGE_GAME               = 11,
} OH_AudioStream_Usage;

typedef int32_t OH_AudioStream_Result;

typedef struct OH_AudioStreamBuilderStruct OH_AudioStreamBuilder;
typedef struct OH_AudioRendererStruct OH_AudioRenderer;

/* 4 callback function pointers (32 bytes on aarch64) — opaque, never called */
typedef struct {
    void *OnWriteData;
    void *OnStreamEvent;
    void *OnInterruptEvent;
    void *OnError;
} OH_AudioRenderer_Callbacks;

/* Dummy opaque object so Create has a non-null pointer to return */
static struct _stub_builder { int dummy; } stub_builder;

/* ---- Builder functions ---- */

OH_AudioStream_Result OH_AudioStreamBuilder_Create(
        OH_AudioStreamBuilder **builder, OH_AudioStream_Type type)
{
    if (builder == ((void*)0))
        return AUDIOSTREAM_ERROR_INVALID_PARAM;
    (void)type;
    *builder = (OH_AudioStreamBuilder *)&stub_builder;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_Destroy(OH_AudioStreamBuilder *builder)
{
    (void)builder;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetSamplingRate(
        OH_AudioStreamBuilder *builder, int32_t rate)
{
    (void)builder; (void)rate;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetChannelCount(
        OH_AudioStreamBuilder *builder, int32_t channelCount)
{
    (void)builder; (void)channelCount;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetSampleFormat(
        OH_AudioStreamBuilder *builder, OH_AudioStream_SampleFormat format)
{
    (void)builder; (void)format;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetEncodingType(
        OH_AudioStreamBuilder *builder, OH_AudioStream_EncodingType encodingType)
{
    (void)builder; (void)encodingType;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetLatencyMode(
        OH_AudioStreamBuilder *builder, OH_AudioStream_LatencyMode latencyMode)
{
    (void)builder; (void)latencyMode;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetRendererInfo(
        OH_AudioStreamBuilder *builder, OH_AudioStream_Usage usage)
{
    (void)builder; (void)usage;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_SetRendererCallback(
        OH_AudioStreamBuilder *builder,
        OH_AudioRenderer_Callbacks callbacks, void *userData)
{
    (void)builder; (void)callbacks; (void)userData;
    return AUDIOSTREAM_SUCCESS;
}

OH_AudioStream_Result OH_AudioStreamBuilder_GenerateRenderer(
        OH_AudioStreamBuilder *builder, OH_AudioRenderer **audioRenderer)
{
    (void)builder;
    if (audioRenderer != ((void*)0))
        *audioRenderer = ((void*)0);
    return AUDIOSTREAM_ERROR_SYSTEM;
}

/* ---- Renderer functions ---- */

OH_AudioStream_Result OH_AudioRenderer_Start(OH_AudioRenderer *renderer)
{
    (void)renderer;
    return AUDIOSTREAM_ERROR_INVALID_PARAM;
}

OH_AudioStream_Result OH_AudioRenderer_Stop(OH_AudioRenderer *renderer)
{
    (void)renderer;
    return AUDIOSTREAM_ERROR_INVALID_PARAM;
}

OH_AudioStream_Result OH_AudioRenderer_Release(OH_AudioRenderer *renderer)
{
    (void)renderer;
    return AUDIOSTREAM_ERROR_INVALID_PARAM;
}
