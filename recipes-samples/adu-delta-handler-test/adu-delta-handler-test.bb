SUMMARY = "Standalone dlopen smoke test for the microsoft/delta:1 download handler"
DESCRIPTION = "Tiny C program that validates libmicrosoft_delta_download_handler.so \
loads, exports the 6 EXPORTED_METHOD entry points the ADU agent looks up, and \
that MicrosoftDeltaDownloadHandlerUtils_ProcessDeltaUpdate(source, delta, target) \
produces a target SWU whose SHA256 matches the build-time recompressed target. \
Used by the QEMU e2e validator (Stage 3) and is also useful for hand-running \
on a real device."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://adu-delta-handler-test.c"

S = "${WORKDIR}"

DEPENDS = "openssl"

# The runtime dependency is only the loader (libdl) + openssl; the handler .so
# is dlopen()'d at runtime by absolute path supplied on the command line, so we
# do NOT take a build-time link dependency on it. We DO want the handler shipped
# in the rootfs so the test can find it — that's expressed at image-install
# time in the kas overlay (kas/qemu-e2e.yml).
RDEPENDS:${PN} = "libcrypto"

do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} \
        ${WORKDIR}/adu-delta-handler-test.c \
        -ldl -lcrypto \
        -o adu-delta-handler-test
}

do_install() {
    install -d ${D}${bindir}
    install -m 0755 adu-delta-handler-test ${D}${bindir}/adu-delta-handler-test
}

FILES:${PN} = "${bindir}/adu-delta-handler-test"
