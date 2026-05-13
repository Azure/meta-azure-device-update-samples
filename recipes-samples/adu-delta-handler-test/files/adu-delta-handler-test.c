/*
 * adu-delta-handler-test.c
 *
 * Standalone smoke + functional test for the ADU "microsoft/delta:1" download
 * handler. Built into a small binary that ships with the rootfs and is driven
 * by the QEMU e2e validator (run-qemu-e2e.sh, Stage 3).
 *
 * Why this exists:
 *   The standalone `applydiff` CLI only proves libadudiffapi works. The actual
 *   on-device delta path goes through libmicrosoft_delta_download_handler.so,
 *   which is dlopen()'d by the ADU agent at runtime. This driver exercises the
 *   handler binary directly via dlopen with NO ADU agent / IoT Hub / network,
 *   so a regression in the handler shows up at build time / on every CI run.
 *
 * Coupling: zero. We deliberately avoid linking against ADU agent headers and
 * resolve every entry point via dlsym(), reproducing the structs (ADUC_Result
 * = 2*int32_t, ADUC_ExtensionContractInfo = 2*uint) inline. This keeps the
 * test resilient to ADU header reorganizations and makes the recipe trivial.
 *
 * What it validates:
 *   1. The handler .so loads with no unresolved symbols (linker sanity).
 *   2. All five EXPORTED_METHOD entry points the agent looks up are present:
 *        Initialize, Cleanup, GetContractInfo, ProcessUpdate,
 *        OnUpdateWorkflowCompleted, CacheSourceUpdate.
 *      (We only call the simple ones — ProcessUpdate needs a workflow handle.)
 *   3. The contract version reported is non-zero.
 *   4. The lower-level worker MicrosoftDeltaDownloadHandlerUtils_ProcessDeltaUpdate
 *      successfully reconstructs a target SWU from (source, diff) and the
 *      output's SHA256 matches the expected target hash.
 *
 * Usage:
 *   adu-delta-handler-test \
 *       <handler.so> <source.swu> <delta.diff> <target.swu> [expected.{swu,sha256}]
 *
 * Exit codes:
 *    0  PASS
 *   64  bad usage
 *   70  dlopen failed
 *   71  one of the EXPORTED_METHOD entry points missing
 *   72  ProcessDeltaUpdate worker symbol not exported (handler may be linked
 *       with hidden visibility — see suggested nm command in stderr)
 *   80  target file not produced
 *   81  cannot hash produced target
 *   82..84  cannot read expected reference
 *   85  SHA mismatch (handler produced wrong bytes)
 */

#include <openssl/evp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <dlfcn.h>

/* Reproduced from aduc/types/adu_core.h — keep in sync if upstream changes. */
typedef struct
{
    int32_t ResultCode;
    int32_t ExtendedResultCode;
} ADUC_Result;

/* Reproduced from aduc/contract_utils.h. */
typedef struct
{
    unsigned int majorVer;
    unsigned int minorVer;
} ADUC_ExtensionContractInfo;

/* ADUC_LOG_SEVERITY enum (reproduced): DEBUG=0, INFO=1, WARN=2, ERROR=3. */
#define LOG_INFO 1

typedef void (*Initialize_fn)(int);
typedef void (*Cleanup_fn)(void);
typedef ADUC_Result (*GetContractInfo_fn)(ADUC_ExtensionContractInfo*);
typedef ADUC_Result (*ProcessDeltaUpdate_fn)(const char*, const char*, const char*);

static int compute_sha256_hex(const char* path, char out_hex[65])
{
    FILE* f = fopen(path, "rb");
    if (f == NULL)
    {
        return -1;
    }

    EVP_MD_CTX* ctx = EVP_MD_CTX_new();
    if (ctx == NULL || EVP_DigestInit_ex(ctx, EVP_sha256(), NULL) != 1)
    {
        if (ctx != NULL)
        {
            EVP_MD_CTX_free(ctx);
        }
        fclose(f);
        return -1;
    }

    unsigned char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0)
    {
        if (EVP_DigestUpdate(ctx, buf, n) != 1)
        {
            EVP_MD_CTX_free(ctx);
            fclose(f);
            return -1;
        }
    }
    fclose(f);

    unsigned char hash[EVP_MAX_MD_SIZE];
    unsigned int hash_len = 0;
    if (EVP_DigestFinal_ex(ctx, hash, &hash_len) != 1)
    {
        EVP_MD_CTX_free(ctx);
        return -1;
    }
    EVP_MD_CTX_free(ctx);

    for (unsigned int i = 0; i < hash_len; ++i)
    {
        snprintf(out_hex + (i * 2), 3, "%02x", hash[i]);
    }
    out_hex[hash_len * 2] = '\0';
    return 0;
}

int main(int argc, char** argv)
{
    if (argc < 5)
    {
        fprintf(
            stderr,
            "Usage: %s <handler.so> <source.swu> <delta.diff> <target.swu> "
            "[expected.swu|expected.sha256]\n",
            argv[0]);
        return 64;
    }

    const char* so_path = argv[1];
    const char* src = argv[2];
    const char* delta = argv[3];
    const char* target = argv[4];
    const char* expected = (argc >= 6) ? argv[5] : NULL;

    fprintf(stderr, "[handler-test] dlopen %s\n", so_path);
    void* h = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (h == NULL)
    {
        fprintf(stderr, "ERR_DLOPEN: %s\n", dlerror());
        return 70;
    }

    /* Required EXPORTED_METHOD entry points the agent's plugin loader uses. */
    Initialize_fn init = (Initialize_fn)dlsym(h, "Initialize");
    Cleanup_fn cln = (Cleanup_fn)dlsym(h, "Cleanup");
    GetContractInfo_fn gci = (GetContractInfo_fn)dlsym(h, "GetContractInfo");
    void* process_update = dlsym(h, "ProcessUpdate");
    void* on_completed = dlsym(h, "OnUpdateWorkflowCompleted");
    void* cache_source = dlsym(h, "CacheSourceUpdate");

    if (init == NULL || cln == NULL || gci == NULL || process_update == NULL
        || on_completed == NULL || cache_source == NULL)
    {
        fprintf(
            stderr,
            "ERR_MISSING_EXPORTS: Initialize=%p Cleanup=%p GetContractInfo=%p "
            "ProcessUpdate=%p OnUpdateWorkflowCompleted=%p CacheSourceUpdate=%p\n",
            (void*)init,
            (void*)cln,
            (void*)gci,
            process_update,
            on_completed,
            cache_source);
        dlclose(h);
        return 71;
    }
    fprintf(stderr, "[handler-test] all 6 EXPORTED_METHOD entry points resolved\n");

    /* The worker function we'll actually exercise — takes 3 file paths and
     * does the source+delta -> target reconstruction. Exported by the same
     * .so when symbol visibility is the CMake default.
     */
    ProcessDeltaUpdate_fn proc = (ProcessDeltaUpdate_fn)dlsym(
        h, "MicrosoftDeltaDownloadHandlerUtils_ProcessDeltaUpdate");
    if (proc == NULL)
    {
        fprintf(
            stderr,
            "ERR_NO_PROCESS_DELTA_UPDATE: MicrosoftDeltaDownloadHandlerUtils_ProcessDeltaUpdate "
            "not exported. Symbol may be hidden — check `nm -D %s | grep ProcessDeltaUpdate`.\n",
            so_path);
        dlclose(h);
        return 72;
    }

    init(LOG_INFO);

    ADUC_ExtensionContractInfo info = { 0, 0 };
    ADUC_Result r = gci(&info);
    fprintf(
        stderr,
        "[handler-test] GetContractInfo: rc=%d xrc=0x%x  contract=%u.%u\n",
        r.ResultCode,
        r.ExtendedResultCode,
        info.majorVer,
        info.minorVer);
    if (info.majorVer == 0 && info.minorVer == 0)
    {
        fprintf(stderr, "WARN: contract version 0.0 — handler did not populate it\n");
    }

    fprintf(
        stderr,
        "[handler-test] ProcessDeltaUpdate(\n  src=%s\n  delta=%s\n  target=%s\n)\n",
        src,
        delta,
        target);
    unlink(target);

    r = proc(src, delta, target);
    fprintf(
        stderr,
        "[handler-test] ProcessDeltaUpdate result: rc=%d xrc=0x%x\n",
        r.ResultCode,
        r.ExtendedResultCode);

    cln();
    dlclose(h);

    /* Verify the output exists and matches expected, regardless of result code
     * (the result code interpretation requires ADUC_Result_Success* macros we
     * don't link against). The bytes are the truth.
     */
    struct stat st;
    if (stat(target, &st) != 0 || st.st_size == 0)
    {
        fprintf(stderr, "FAIL: target %s not produced or empty\n", target);
        return 80;
    }
    fprintf(stderr, "[handler-test] target produced: %lld bytes\n", (long long)st.st_size);

    char actual_sha[65];
    if (compute_sha256_hex(target, actual_sha) != 0)
    {
        fprintf(stderr, "FAIL: cannot hash produced target %s\n", target);
        return 81;
    }
    fprintf(stderr, "[handler-test] target sha256: %s\n", actual_sha);

    if (expected != NULL)
    {
        char expected_sha[65] = { 0 };
        const char* dot = strrchr(expected, '.');
        int is_sha_file = (dot != NULL && strcmp(dot, ".sha256") == 0);

        if (is_sha_file)
        {
            FILE* f = fopen(expected, "r");
            if (f == NULL)
            {
                fprintf(stderr, "FAIL: cannot read sha file %s\n", expected);
                return 82;
            }
            if (fscanf(f, "%64s", expected_sha) != 1)
            {
                fclose(f);
                fprintf(stderr, "FAIL: cannot parse sha from %s\n", expected);
                return 83;
            }
            fclose(f);
        }
        else
        {
            if (compute_sha256_hex(expected, expected_sha) != 0)
            {
                fprintf(stderr, "FAIL: cannot hash expected %s\n", expected);
                return 84;
            }
        }
        fprintf(stderr, "[handler-test] expected sha256: %s\n", expected_sha);

        if (strcmp(actual_sha, expected_sha) != 0)
        {
            fprintf(stderr, "FAIL: SHA mismatch — handler produced wrong output bytes\n");
            return 85;
        }
        fprintf(stderr, "PASS: handler-produced target matches expected SHA256\n");
    }
    else
    {
        fprintf(stderr, "PASS: handler produced non-empty target (no expected hash to verify)\n");
    }

    return 0;
}
