# ADU SWUpdate Image - Version 4 (demo-realistic target)
#
# v4 builds on the same flow as v1/v2/v3 (mutate adu-base-image.ext4.gz +
# /etc/adu-version) but additionally injects a ~4 MiB incompressible demo
# payload at /opt/adu-demo/agent-payload-v4.bin. This produces a realistic
# v3 -> v4 delta (~3-5 MB) for "delta vs full" customer demos. v1/v2/v3 are
# intentionally left unchanged so existing test signal is preserved.

require adu-update-image-common.inc

DESCRIPTION = "ADU swupdate image v4 (target for delta, includes demo payload)"

ADU_SOFTWARE_VERSION = "1.0.0.4"

export IMAGE_LINK_NAME = "adu-update-image-v4"

# Ship the static demo payload alongside the recipe; do_unpack stages it in
# ${WORKDIR}. The postprocess hook below injects it into the rootfs ext4.
SRC_URI += "file://agent-payload-v4.bin"

# Where the payload lives inside the deployed rootfs. Kept under /opt so it
# is never on PATH and cannot be exec'd accidentally by anyone poking around.
ADU_DEMO_PAYLOAD_DEST = "/opt/adu-demo/agent-payload-v4.bin"
ADU_DEMO_PAYLOAD_NAME = "agent-payload-v4.bin"

# Append to the postprocess command list defined in adu-update-image-common.inc.
# The common.inc do_create_versioned_image will invoke adu_inject_demo_payload
# after /etc/adu-version has been written and before SWU compression.
ADU_VERSIONED_IMAGE_POSTPROCESS_COMMAND += "adu_inject_demo_payload; "

# Idempotent: rm target (ignore ENOENT), mkdir parents if needed, write,
# then stat-verify. Hard-fails the task on any unexpected condition so we
# can never silently ship a v4 SWU without the payload.
adu_inject_demo_payload() {
    local rootfs="${ADU_ROOTFS_EXT4}"
    local dest="${ADU_DEMO_PAYLOAD_DEST}"
    local name="${ADU_DEMO_PAYLOAD_NAME}"
    local dest_dir
    dest_dir="$(dirname "$dest")"

    # Locate the unpacked payload. Yocto versions differ on where file:// URIs
    # are staged: classic releases use ${WORKDIR}, scarthgap+ may use
    # ${UNPACKDIR} (typically ${WORKDIR}/sources-unpack). Probe both.
    local src=""
    for cand in "${UNPACKDIR}/$name" "${WORKDIR}/$name" "${WORKDIR}/sources-unpack/$name"; do
        if [ -f "$cand" ]; then src="$cand"; break; fi
    done
    if [ -z "$src" ]; then
        bberror "Could not locate unpacked $name in any of:"
        bberror "  ${UNPACKDIR}/$name"
        bberror "  ${WORKDIR}/$name"
        bberror "  ${WORKDIR}/sources-unpack/$name"
        bbfatal "Demo payload source not found"
    fi

    bbnote "----------------------------------------------------------------------"
    bbnote "adu_inject_demo_payload"
    bbnote "  rootfs: $rootfs"
    bbnote "  src:    $src"
    bbnote "  dest:   $dest"
    bbnote "----------------------------------------------------------------------"

    if [ ! -f "$rootfs" ]; then
        bbfatal "Rootfs ext4 not found at $rootfs"
    fi

    # Sanity log of the source blob.
    bbnote "  src size:   $(wc -c < "$src") bytes"
    bbnote "  src sha256: $(sha256sum "$src" | awk '{print $1}')"

    # Ensure every parent directory of dest exists. debugfs has no `mkdir -p`,
    # so walk the components and create each missing one.
    local accum=""
    local IFS_BACKUP="$IFS"
    IFS='/'
    set -- ${dest_dir#/}
    IFS="$IFS_BACKUP"
    for component in "$@"; do
        [ -z "$component" ] && continue
        accum="$accum/$component"
        # `ls` on a nonexistent dir prints "File not found" to stderr.
        if debugfs -R "ls -d $accum" "$rootfs" 2>&1 | grep -q "File not found"; then
            bbnote "  creating dir: $accum"
            debugfs -w -R "mkdir $accum" "$rootfs" >/dev/null 2>&1 \
                || bbfatal "debugfs mkdir $accum failed"
        fi
    done

    # Remove any prior file at the destination (idempotency for partial reruns).
    debugfs -w -R "rm $dest" "$rootfs" >/dev/null 2>&1 || true

    # Write the payload into the ext4 image.
    debugfs -w -R "write $src $dest" "$rootfs" \
        || bbfatal "debugfs write $src -> $dest failed"

    # Validate: stat the inode and confirm size matches src. Catch silent
    # debugfs failures (write-and-keep-going behaviour on full filesystems).
    local stat_out
    stat_out="$(debugfs -R "stat $dest" "$rootfs" 2>&1 || true)"
    bbnote "stat $dest:"
    echo "$stat_out" | while read -r _line; do bbnote "  $_line"; done

    local src_size
    src_size="$(wc -c < "$src")"
    if ! echo "$stat_out" | grep -qE "Size: *${src_size}\b"; then
        bberror "Post-write stat reports unexpected size for $dest"
        bberror "Expected: $src_size bytes"
        bberror "Got: $(echo "$stat_out" | grep -i '^Size:')"
        bbfatal "Demo payload injection verification failed"
    fi

    bbnote "✓ Demo payload injected and verified ($src_size bytes at $dest)"
}

do_swuimage[depends] += "adu-base-image:do_image_complete"

python do_set_version() {
    import os
    version = d.getVar('ADU_SOFTWARE_VERSION')
    bb.note("Building ADU Update Image Version: %s" % version)
}

addtask set_version before do_swuimage after do_unpack

# Automatically clean SWU artifacts when recipe is cleaned/rebuilt
clean_swu_artifacts () {
    for swu_file in ${DEPLOY_DIR_IMAGE}/${IMAGE_NAME}*.swu ${DEPLOY_DIR_IMAGE}/${IMAGE_LINK_NAME}*.swu; do
        if [ -f "$swu_file" ]; then
            bbwarn "Cleaning stale SWU artifact: $swu_file"
            rm -f "$swu_file"
        fi
    done
}

CLEANFUNCS += "clean_swu_artifacts"
