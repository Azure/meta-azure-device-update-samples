SUMMARY = "ADU Script Handlers"
DESCRIPTION = "Custom script handlers for Azure Device Update testing and operations. \
               Includes delta source caching handler that verifies recompressed SWU files are cached correctly."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://microsoft-delta-source-caching.sh"

S = "${WORKDIR}"

do_install() {
    # Install script handler to /usr/lib/adu (same location as yocto-a-b-update.sh)
    install -d ${D}${libdir}/adu
    install -m 0755 ${WORKDIR}/microsoft-delta-source-caching.sh ${D}${libdir}/adu/
}

FILES:${PN} = "${libdir}/adu/microsoft-delta-source-caching.sh"

# Runtime dependencies
RDEPENDS:${PN} = "bash azure-device-update swupdate u-boot-fw-utils"

inherit allarch
